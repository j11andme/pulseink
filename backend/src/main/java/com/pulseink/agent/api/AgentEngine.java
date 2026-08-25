package com.pulseink.agent.api;

import com.pulseink.domain.execution.ExecutionMode;

/**
 * Unified execution engine contract for DIRECT and REACT modes. Implementations must enforce
 * budget, policy and observer contracts; the model and tools can never bypass these checks.
 */
public interface AgentEngine {

    ExecutionMode supportedMode();

    default AgentExecutionResult execute(AgentExecutionRequest request) {
        return execute(request, event -> {});
    }

    AgentExecutionResult execute(
            AgentExecutionRequest request,
            AgentExecutionObserver observer);
}
