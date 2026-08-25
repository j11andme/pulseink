package com.pulseink.repository.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.service.memory.RunWorkingMemory;
import com.pulseink.service.memory.RunWorkingMemoryCache;
import com.pulseink.support.MemoryTestContainers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
class RedisRunWorkingMemoryCacheIT {

    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00Z");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MemoryTestContainers::mysqlUrl);
        registry.add("spring.datasource.username", MemoryTestContainers::mysqlUsername);
        registry.add("spring.datasource.password", MemoryTestContainers::mysqlPassword);
        registry.add("spring.data.redis.url", MemoryTestContainers::redisUrl);
    }

    @Autowired RunWorkingMemoryCache cache;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @BeforeEach
    void clean() {
        cache.invalidate(2L);
        cache.invalidate(3L);
    }

    @Test
    void roundTripHitsAndInvalidateMisses() {
        var memory = memory(2L, 7L, "ARTIFACT", List.of(summary("marker")));

        assertThat(cache.load(2L)).isEmpty();
        cache.put(2L, memory);
        assertThat(cache.load(2L)).get().isEqualTo(memory);
        cache.invalidate(2L);
        assertThat(cache.load(2L)).isEmpty();
    }

    @Test
    void wrongSchemaOrCorruptedJsonAreTreatedAsMisses() {
        cache.put(2L, memory(2L, 7L, "ARTIFACT", List.of()));
        redisTemplate.opsForValue().set("pulseink:run:2:memory:v1", "{not-json",
                Duration.ofMinutes(1));
        assertThat(cache.load(2L)).isEmpty();
        redisTemplate.opsForValue().set("pulseink:run:2:memory:v1",
                "{\"schemaVersion\":99,\"runId\":2,\"checkpointType\":\"ARTIFACT\","
                        + "\"lastCompletedRound\":0,\"lastPersistedEventSequence\":0,"
                        + "\"createdAt\":\"2026-08-14T08:00:00Z\",\"validArtifacts\":[],"
                        + "\"budgetSnapshot\":{\"modelCallsUsed\":0,\"toolCallsUsed\":0,"
                        + "\"tokensUsed\":0,\"reactRoundsUsed\":0}}",
                Duration.ofMinutes(1));
        assertThat(cache.load(2L)).isEmpty();
    }

    @Test
    void differentRunsUseDifferentKeys() {
        cache.put(2L, memory(2L, 7L, "ARTIFACT", List.of()));
        assertThat(cache.load(3L)).isEmpty();
    }

    private RunWorkingMemory memory(long runId, long sequence, String type,
                                    List<RunWorkingMemory.ArtifactSummary> artifacts) {
        return new RunWorkingMemory(runId, type, 1, 0, sequence, NOW, artifacts,
                BudgetSnapshot.ZERO);
    }

    private static RunWorkingMemory.ArtifactSummary summary(String marker) {
        return new RunWorkingMemory.ArtifactSummary("artifact-1", "create-main",
                ArtifactType.CONTENT_DRAFT, 1, ArtifactStatus.VALID, marker);
    }
}
