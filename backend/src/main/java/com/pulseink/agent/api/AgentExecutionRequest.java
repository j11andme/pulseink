package com.pulseink.agent.api;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.execution.ExecutionMode;
import java.util.List;
import java.util.Objects;

/**
 * Immutable request to execute an agent. All mutable inputs (prior artifacts) are defensively
 * copied. The request never carries raw prompts or secrets; the {@code objective} is a
 * normalized summary that the engine expands into a system/user prompt internally. The
 * {@code taskId} identifies the current plan task; legacy calls default to
 * {@code AgentArtifact.UNIFIED_TASK_ID}. {@code campaignChannels} feeds the role-context
 * assembler so CHANNEL-scoped approved insights are matched against the campaign's channels.
 */
public record AgentExecutionRequest(
        long runId,
        String requestId,
        ExecutionMode mode,
        AgentProfile profile,
        String objective,
        List<AgentArtifact> priorArtifacts,
        BudgetSnapshot budgetSnapshot,
        ApprovalState approvalState,
        String taskId,
        List<CampaignChannel> campaignChannels,
        ExecutionOwnershipGuard guard) {

    public AgentExecutionRequest {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        if (requestId == null) {
            throw new IllegalArgumentException("requestId must not be null");
        }
        if (requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        mode = Objects.requireNonNull(mode, "mode must not be null");
        profile = Objects.requireNonNull(profile, "profile must not be null");
        Objects.requireNonNull(objective, "objective must not be null");
        if (objective.isBlank()) {
            throw new IllegalArgumentException("objective must not be blank");
        }
        priorArtifacts = priorArtifacts == null ? List.of() : List.copyOf(priorArtifacts);
        Objects.requireNonNull(budgetSnapshot, "budgetSnapshot must not be null");
        approvalState = Objects.requireNonNull(approvalState, "approvalState must not be null");
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        campaignChannels = campaignChannels == null
                ? List.of()
                : List.copyOf(campaignChannels);
        guard = guard == null ? ExecutionOwnershipGuard.noop() : guard;
    }

    /**
     * Legacy 10-arg constructor without the ownership guard (noop).
     */
    public AgentExecutionRequest(
            long runId,
            String requestId,
            ExecutionMode mode,
            AgentProfile profile,
            String objective,
            List<AgentArtifact> priorArtifacts,
            BudgetSnapshot budgetSnapshot,
            ApprovalState approvalState,
            String taskId,
            List<CampaignChannel> campaignChannels) {
        this(runId, requestId, mode, profile, objective, priorArtifacts, budgetSnapshot,
                approvalState, taskId, campaignChannels, ExecutionOwnershipGuard.noop());
    }

    /**
     * Legacy 9-arg constructor for DIRECT/REACT; taskId defaults to the unified task id.
     */
    public AgentExecutionRequest(
            long runId,
            String requestId,
            ExecutionMode mode,
            AgentProfile profile,
            String objective,
            List<AgentArtifact> priorArtifacts,
            BudgetSnapshot budgetSnapshot,
            ApprovalState approvalState,
            String taskId) {
        this(runId, requestId, mode, profile, objective, priorArtifacts, budgetSnapshot,
                approvalState, taskId, List.of());
    }

    /**
     * Legacy 8-arg constructor for DIRECT/REACT; taskId defaults to the unified task id.
     */
    public AgentExecutionRequest(
            long runId,
            String requestId,
            ExecutionMode mode,
            AgentProfile profile,
            String objective,
            List<AgentArtifact> priorArtifacts,
            BudgetSnapshot budgetSnapshot,
            ApprovalState approvalState) {
        this(runId, requestId, mode, profile, objective, priorArtifacts, budgetSnapshot,
                approvalState, AgentArtifact.UNIFIED_TASK_ID);
    }
}
