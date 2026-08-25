package com.pulseink.agent.plan;

import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.orchestration.AgentRole;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable plan task: a single role invocation producing exactly one artifact type.
 */
public record PlanTask(
        String taskId,
        AgentRole role,
        String objective,
        List<String> dependsOn,
        Set<ArtifactType> requiredArtifactTypes,
        ArtifactType outputArtifactType,
        PlanTaskAccess access) {

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public PlanTask {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (!TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new IllegalArgumentException("taskId must match [a-z][a-z0-9-]{0,63}: " + taskId);
        }
        role = Objects.requireNonNull(role, "role must not be null");
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("objective must not be blank");
        }
        dependsOn = List.copyOf(Objects.requireNonNull(dependsOn, "dependsOn must not be null"));
        requiredArtifactTypes = Set.copyOf(Objects.requireNonNull(
                requiredArtifactTypes, "requiredArtifactTypes must not be null"));
        outputArtifactType = Objects.requireNonNull(
                outputArtifactType, "outputArtifactType must not be null");
        access = Objects.requireNonNull(access, "access must not be null");
    }
}
