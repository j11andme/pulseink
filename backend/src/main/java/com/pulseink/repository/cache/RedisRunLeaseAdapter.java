package com.pulseink.repository.cache;

import com.pulseink.service.campaign.RunLease;
import com.pulseink.service.campaign.RunLeasePort;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis run lease: {@code SET key ownerId|token NX PX ttl} to acquire, Lua compare-and-PEXPIRE
 * to renew and Lua compare-and-DEL to release, so a stale owner can never evict a newer lease.
 */
public class RedisRunLeaseAdapter implements RunLeasePort {

    private static final String KEY_PREFIX = "pulseink:run:";
    private static final String KEY_SUFFIX = ":lease";
    private static final String RENEW_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """;
    private static final String RELEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRunLeaseAdapter(StringRedisTemplate redis) {
        this(redis, Clock.systemUTC());
    }

    public RedisRunLeaseAdapter(StringRedisTemplate redis, Clock clock) {
        this.redis = Objects.requireNonNull(redis);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<RunLease> tryAcquire(long runId, String ownerId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        String value = ownerId + "|" + token;
        Boolean acquired = redis.opsForValue().setIfAbsent(
                key(runId), value, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        return Optional.of(new RunLease(runId, ownerId, token, clock.instant()));
    }

    @Override
    public boolean renew(RunLease lease, Duration ttl) {
        Long result = redis.execute(new DefaultRedisScript<>(
                        RENEW_SCRIPT, Long.class),
                java.util.List.of(key(lease.runId())),
                lease.value(), String.valueOf(ttl.toMillis()));
        return result != null && result == 1L;
    }

    @Override
    public boolean release(RunLease lease) {
        Long result = redis.execute(new DefaultRedisScript<>(
                        RELEASE_SCRIPT, Long.class),
                java.util.List.of(key(lease.runId())),
                lease.value());
        return result != null && result == 1L;
    }

    private static String key(long runId) {
        return KEY_PREFIX + runId + KEY_SUFFIX;
    }
}
