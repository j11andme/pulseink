package com.pulseink.agent.artifact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, versioned, typed artifact produced by an agent execution. All mutable inputs
 * (content map, source refs) are deep-copied at construction so callers and the model can never
 * mutate a persisted snapshot.
 */
@com.fasterxml.jackson.annotation.JsonAutoDetect(
        fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
public final class AgentArtifact {

    public static final String SCHEMA_VERSION = "artifact-v1";
    public static final String UNIFIED_TASK_ID = "unified";

    private final String artifactId;
    private final long runId;
    private final String taskId;
    private final ArtifactType type;
    private final String schemaVersion;
    private final int artifactVersion;
    private final ArtifactStatus status;
    private final Map<String, Object> content;
    private final List<String> sourceRefs;
    private final Instant createdAt;

    private AgentArtifact(String artifactId, long runId, String taskId, ArtifactType type,
                          String schemaVersion, int artifactVersion, ArtifactStatus status,
                          Map<String, Object> content, List<String> sourceRefs, Instant createdAt) {
        this.artifactId = requireNonBlank(artifactId, "artifactId");
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        this.runId = runId;
        this.taskId = requireNonBlank(taskId, "taskId");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.schemaVersion = SCHEMA_VERSION;
        if (artifactVersion <= 0) {
            throw new IllegalArgumentException("artifactVersion must be positive");
        }
        this.artifactVersion = artifactVersion;
        this.status = Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isEmpty()) {
            throw new IllegalArgumentException("content must not be empty");
        }
        this.content = deepImmutable(content);
        Objects.requireNonNull(sourceRefs, "sourceRefs must not be null");
        this.sourceRefs = List.copyOf(sourceRefs);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static AgentArtifact create(
            String artifactId, long runId, String taskId, ArtifactType type,
            int artifactVersion, Map<String, Object> content,
            List<String> sourceRefs, Instant createdAt) {
        return new AgentArtifact(artifactId, runId, taskId, type, SCHEMA_VERSION,
                artifactVersion, ArtifactStatus.VALID, content, sourceRefs, createdAt);
    }

    /**
     * Restores a persisted artifact, rejecting unknown schema versions.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static AgentArtifact restore(
            @com.fasterxml.jackson.annotation.JsonProperty("artifactId") String artifactId,
            @com.fasterxml.jackson.annotation.JsonProperty("runId") long runId,
            @com.fasterxml.jackson.annotation.JsonProperty("taskId") String taskId,
            @com.fasterxml.jackson.annotation.JsonProperty("type") ArtifactType type,
            @com.fasterxml.jackson.annotation.JsonProperty("schemaVersion") String schemaVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("artifactVersion") int artifactVersion,
            @com.fasterxml.jackson.annotation.JsonProperty("status") ArtifactStatus status,
            @com.fasterxml.jackson.annotation.JsonProperty("content") Map<String, Object> content,
            @com.fasterxml.jackson.annotation.JsonProperty("sourceRefs") List<String> sourceRefs,
            @com.fasterxml.jackson.annotation.JsonProperty("createdAt") Instant createdAt) {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unknown schemaVersion: " + schemaVersion);
        }
        return new AgentArtifact(artifactId, runId, taskId, type, SCHEMA_VERSION,
                artifactVersion, status, content, sourceRefs, createdAt);
    }

    public AgentArtifact withStatus(ArtifactStatus newStatus) {
        return new AgentArtifact(artifactId, runId, taskId, type, schemaVersion,
                artifactVersion, newStatus, content, sourceRefs, createdAt);
    }

    public String artifactId() { return artifactId; }
    public long runId() { return runId; }
    public String taskId() { return taskId; }
    public ArtifactType type() { return type; }
    public String schemaVersion() { return schemaVersion; }
    public int artifactVersion() { return artifactVersion; }
    public ArtifactStatus status() { return status; }
    public Map<String, Object> content() { return content; }
    public List<String> sourceRefs() { return sourceRefs; }
    public Instant createdAt() { return createdAt; }

    private static String requireNonBlank(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static Map<String, Object> deepImmutable(Map<String, Object> source) {
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            copy.put(entry.getKey(), deepImmutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object deepImmutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepImmutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>();
            for (var element : list) {
                copy.add(deepImmutableValue(element));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            var copy = new HashSet<Object>();
            for (var element : set) {
                copy.add(deepImmutableValue(element));
            }
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        return value;
    }
}
