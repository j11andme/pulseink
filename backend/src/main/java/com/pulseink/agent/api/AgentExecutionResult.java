package com.pulseink.agent.api;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.domain.execution.ExecutionMode;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of an agent execution. Carries the final artifacts, budget snapshot, terminal
 * reason and deterministic metrics. Never carries prompts, secrets, hidden reasoning or provider
 * stack traces.
 */
public record AgentExecutionResult(
        long runId,
        ExecutionMode mode,
        List<AgentArtifact> artifacts,
        BudgetSnapshot finalBudget,
        AgentTerminalReason terminalReason,
        Metrics metrics) {

    public AgentExecutionResult {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        mode = Objects.requireNonNull(mode, "mode must not be null");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        Objects.requireNonNull(finalBudget, "finalBudget must not be null");
        terminalReason = Objects.requireNonNull(terminalReason, "terminalReason must not be null");
        metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    public record Metrics(
            int modelCalls,
            int toolCalls,
            long totalTokens,
            int reactRounds) {
    }
}
