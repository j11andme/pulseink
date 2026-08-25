package com.pulseink.agent.orchestration;

import com.pulseink.agent.api.ExecutionOwnershipGuard;
import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.plan.PlanTask;
import com.pulseink.agent.tool.ApprovalState;
import java.util.List;
import java.util.Objects;

/**
 * Immutable request to run a single role task with its dependency artifacts. The ownership
 * guard is the same guard the root request carries; parallel tasks never re-acquire leases.
 */
public record RoleTaskRequest(
        long runId,
        String requestId,
        PlanTask task,
        String campaignContext,
        List<AgentArtifact> dependencyArtifacts,
        List<AgentArtifact> taskArtifacts,
        ExecutionBudget budget,
        ApprovalState approvalState,
        ExecutionOwnershipGuard guard) {

    public RoleTaskRequest {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        task = Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(campaignContext, "campaignContext must not be null");
        dependencyArtifacts = dependencyArtifacts == null
                ? List.of()
                : List.copyOf(dependencyArtifacts);
        taskArtifacts = taskArtifacts == null ? List.of() : List.copyOf(taskArtifacts);
        budget = Objects.requireNonNull(budget, "budget must not be null");
        approvalState = Objects.requireNonNull(approvalState, "approvalState must not be null");
        guard = guard == null ? ExecutionOwnershipGuard.noop() : guard;
    }

    public RoleTaskRequest(long runId, String requestId, PlanTask task,
                           String campaignContext, List<AgentArtifact> dependencyArtifacts,
                           List<AgentArtifact> taskArtifacts,
                           ExecutionBudget budget, ApprovalState approvalState) {
        this(runId, requestId, task, campaignContext, dependencyArtifacts, taskArtifacts,
                budget, approvalState, ExecutionOwnershipGuard.noop());
    }

    public RoleTaskRequest(long runId, String requestId, PlanTask task,
                           String campaignContext, List<AgentArtifact> dependencyArtifacts,
                           ExecutionBudget budget, ApprovalState approvalState) {
        this(runId, requestId, task, campaignContext, dependencyArtifacts, List.of(),
                budget, approvalState);
    }
}
