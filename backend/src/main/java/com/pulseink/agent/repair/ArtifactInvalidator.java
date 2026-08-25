package com.pulseink.agent.repair;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Replaces affected VALID artifact snapshots with immutable INVALIDATED snapshots. */
public final class ArtifactInvalidator {

    public InvalidationResult invalidate(List<AgentArtifact> history,
                                         Set<String> taskIds,
                                         boolean includePlan) {
        if (history == null || taskIds == null) {
            throw new IllegalArgumentException("history and taskIds must not be null");
        }
        var updated = new ArrayList<AgentArtifact>(history.size());
        var invalidated = new ArrayList<AgentArtifact>();
        for (var artifact : history) {
            boolean selected = artifact.status() == ArtifactStatus.VALID
                    && (taskIds.contains(artifact.taskId())
                            || (includePlan && artifact.type() == ArtifactType.PLAN));
            var snapshot = selected
                    ? artifact.withStatus(ArtifactStatus.INVALIDATED)
                    : artifact;
            updated.add(snapshot);
            if (selected) {
                invalidated.add(snapshot);
            }
        }
        return new InvalidationResult(updated, invalidated);
    }

    public record InvalidationResult(
            List<AgentArtifact> history,
            List<AgentArtifact> invalidatedArtifacts) {
        public InvalidationResult {
            history = List.copyOf(history);
            invalidatedArtifacts = List.copyOf(invalidatedArtifacts);
        }
    }
}
