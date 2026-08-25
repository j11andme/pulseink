package com.pulseink.repository.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.service.campaign.RunLease;
import com.pulseink.service.campaign.RunLeasePort;
import com.pulseink.support.MemoryTestContainers;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake",
        "pulseink.publication.worker-enabled=false",
        "pulseink.feedback.consumer-enabled=false",
        "pulseink.memory.index-worker-enabled=false",
        "pulseink.run-lease.enabled=false"
})
class RedisRunLeaseAdapterIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MemoryTestContainers::mysqlUrl);
        registry.add("spring.datasource.username", MemoryTestContainers::mysqlUsername);
        registry.add("spring.datasource.password", MemoryTestContainers::mysqlPassword);
        registry.add("spring.data.redis.url", MemoryTestContainers::redisUrl);
    }

    @Autowired RunLeasePort port;

    @Test
    void twoOwnersCompeteForTheSameRunButDifferentRunsAreParallel() {
        var ownerA = port.tryAcquire(101L, "owner-a", Duration.ofSeconds(30));
        var ownerB = port.tryAcquire(101L, "owner-b", Duration.ofSeconds(30));

        assertThat(ownerA).isPresent();
        assertThat(ownerB).isEmpty();
        assertThat(port.tryAcquire(102L, "owner-b", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void renewAndReleaseAreTokenSafe() {
        var lease = port.tryAcquire(103L, "owner-a", Duration.ofSeconds(30)).orElseThrow();
        var forged = new RunLease(103L, "owner-a", "forged-token", lease.acquiredAt());

        assertThat(port.renew(lease, Duration.ofSeconds(30))).isTrue();
        assertThat(port.renew(forged, Duration.ofSeconds(30))).isFalse();
        assertThat(port.release(forged)).isFalse();
        assertThat(port.tryAcquire(103L, "owner-b", Duration.ofSeconds(1))).isEmpty();

        assertThat(port.release(lease)).isTrue();
        assertThat(port.tryAcquire(103L, "owner-b", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void ttlExpiryAllowsAnotherOwnerToTakeOver() {
        assertThat(port.tryAcquire(104L, "owner-a", Duration.ofMillis(400))).isPresent();

        awaitUntil(() -> port.tryAcquire(104L, "owner-b", Duration.ofSeconds(30)).isPresent());
    }

    @Test
    void releaseFreesTheKeyImmediately() {
        var lease = port.tryAcquire(105L, "owner-a", Duration.ofSeconds(30)).orElseThrow();

        assertThat(port.release(lease)).isTrue();
        assertThat(port.tryAcquire(105L, "owner-b", Duration.ofSeconds(30))).isPresent();
    }

    private static void awaitUntil(Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("condition was not met within the deadline");
    }
}
