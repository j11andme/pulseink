package com.pulseink.service.memory;

import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * Reconstructable working memory projection of the latest MySQL checkpoint: VALID artifact
 * summaries plus the budget snapshot. Redis only caches this shape; the checkpoint remains
 * authoritative.
 */
public record RunWorkingMemory(
        long runId,
        String checkpointType,
        int schemaVersion,
        int lastCompletedRound,
        long lastPersistedEventSequence,
        Instant createdAt,
        List<ArtifactSummary> validArtifacts,
        BudgetSnapshot budgetSnapshot) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public RunWorkingMemory {
        validArtifacts = List.copyOf(validArtifacts);
    }

    public static RunWorkingMemory empty(long runId) {
        return new RunWorkingMemory(runId, "NONE", SUPPORTED_SCHEMA_VERSION,
                0, 0L, Instant.EPOCH, List.of(), BudgetSnapshot.ZERO);
    }

    public record ArtifactSummary(
            String artifactId,
            String taskId,
            ArtifactType type,
            int artifactVersion,
            ArtifactStatus status,
            String contentSummary) {
    }
}
