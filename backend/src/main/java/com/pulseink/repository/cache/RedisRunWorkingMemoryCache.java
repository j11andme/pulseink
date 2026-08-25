package com.pulseink.repository.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.service.memory.RunWorkingMemory;
import com.pulseink.service.memory.RunWorkingMemoryCache;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis hot cache for run working memory. The value is a strict, versioned projection of the
 * MySQL checkpoint: any parse failure, wrong schema or missing key is simply a miss that
 * rebuilds from MySQL. Redis is never the authority.
 */
public class RedisRunWorkingMemoryCache implements RunWorkingMemoryCache {

    private static final String KEY_PREFIX = "pulseink:run:";
    private static final String KEY_SUFFIX = ":memory:v1";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisRunWorkingMemoryCache(StringRedisTemplate redis,
                                      ObjectMapper objectMapper,
                                      Duration ttl) {
        this.redis = Objects.requireNonNull(redis);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.ttl = Objects.requireNonNull(ttl);
    }

    @Override
    public Optional<RunWorkingMemory> load(long runId) {
        String json;
        try {
            json = redis.opsForValue().get(key(runId));
        } catch (RuntimeException redisDown) {
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(json));
        } catch (RuntimeException corrupted) {
            return Optional.empty();
        }
    }

    @Override
    public void put(long runId, RunWorkingMemory memory) {
        redis.opsForValue().set(key(runId), write(memory), ttl);
    }

    @Override
    public void invalidate(long runId) {
        try {
            redis.delete(key(runId));
        } catch (RuntimeException redisDown) {
            // cache invalidation is best effort; MySQL remains authoritative
        }
    }

    private static String key(long runId) {
        return KEY_PREFIX + runId + KEY_SUFFIX;
    }

    private String write(RunWorkingMemory memory) {
        var root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", memory.schemaVersion());
        root.put("runId", memory.runId());
        root.put("checkpointType", memory.checkpointType());
        root.put("lastCompletedRound", memory.lastCompletedRound());
        root.put("lastPersistedEventSequence", memory.lastPersistedEventSequence());
        root.put("createdAt", memory.createdAt().toString());
        var artifacts = new ArrayList<Map<String, Object>>();
        for (var artifact : memory.validArtifacts()) {
            artifacts.add(Map.of(
                    "artifactId", artifact.artifactId(),
                    "taskId", artifact.taskId(),
                    "type", artifact.type().name(),
                    "artifactVersion", artifact.artifactVersion(),
                    "status", artifact.status().name(),
                    "contentSummary", artifact.contentSummary()));
        }
        root.put("validArtifacts", artifacts);
        root.put("budgetSnapshot", Map.of(
                "modelCallsUsed", memory.budgetSnapshot().modelCallsUsed(),
                "toolCallsUsed", memory.budgetSnapshot().toolCallsUsed(),
                "tokensUsed", memory.budgetSnapshot().tokensUsed(),
                "reactRoundsUsed", memory.budgetSnapshot().reactRoundsUsed()));
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("working memory cannot be serialized", exception);
        }
    }

    private RunWorkingMemory parse(String json) {
        JsonNode root = readTree(json);
        int schemaVersion = root.path("schemaVersion").asInt(-1);
        if (schemaVersion != RunWorkingMemory.SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported cache schema " + schemaVersion);
        }
        long runId = root.path("runId").asLong(-1);
        if (runId <= 0) {
            throw new IllegalStateException("cache entry is missing runId");
        }
        var artifacts = new ArrayList<RunWorkingMemory.ArtifactSummary>();
        for (JsonNode node : root.path("validArtifacts")) {
            artifacts.add(new RunWorkingMemory.ArtifactSummary(
                    node.path("artifactId").asText(),
                    node.path("taskId").asText(),
                    ArtifactType.valueOf(node.path("type").asText()),
                    node.path("artifactVersion").asInt(),
                    ArtifactStatus.valueOf(node.path("status").asText()),
                    node.path("contentSummary").asText()));
        }
        JsonNode budget = root.path("budgetSnapshot");
        return new RunWorkingMemory(
                runId,
                root.path("checkpointType").asText(),
                schemaVersion,
                root.path("lastCompletedRound").asInt(),
                root.path("lastPersistedEventSequence").asLong(),
                Instant.parse(root.path("createdAt").asText()),
                artifacts,
                new BudgetSnapshot(
                        budget.path("modelCallsUsed").asInt(),
                        budget.path("toolCallsUsed").asInt(),
                        budget.path("tokensUsed").asLong(),
                        budget.path("reactRoundsUsed").asInt()));
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cache JSON is invalid", exception);
        }
    }
}
