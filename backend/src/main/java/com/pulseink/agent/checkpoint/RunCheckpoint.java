package com.pulseink.agent.checkpoint;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.budget.BudgetSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable persisted checkpoint of a run: completed artifacts, budget usage, round count and
 * the last persisted event sequence. Only {@code schemaVersion=1} is supported.
 */
public record RunCheckpoint(
        long runId,
        String checkpointType,
        int schemaVersion,
        List<AgentArtifact> artifacts,
        BudgetSnapshot budgetSnapshot,
        int lastCompletedRound,
        long lastPersistedEventSequence,
        Instant createdAt) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public RunCheckpoint {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        Objects.requireNonNull(checkpointType, "checkpointType must not be null");
        if (checkpointType.isBlank()) {
            throw new IllegalArgumentException("checkpointType must not be blank");
        }
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported checkpoint schemaVersion: " + schemaVersion);
        }
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        budgetSnapshot = Objects.requireNonNull(budgetSnapshot, "budgetSnapshot must not be null");
        if (lastCompletedRound < 0) {
            throw new IllegalArgumentException("lastCompletedRound must not be negative");
        }
        if (lastPersistedEventSequence < 0) {
            throw new IllegalArgumentException(
                    "lastPersistedEventSequence must not be negative");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static RunCheckpoint of(
            long runId,
            String checkpointType,
            List<AgentArtifact> artifacts,
            BudgetSnapshot budgetSnapshot,
            int lastCompletedRound,
            long lastPersistedEventSequence,
            Instant createdAt) {
        return new RunCheckpoint(
                runId, checkpointType, SUPPORTED_SCHEMA_VERSION,
                artifacts, budgetSnapshot, lastCompletedRound,
                lastPersistedEventSequence, createdAt);
    }
}
