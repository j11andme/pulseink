package com.pulseink.agent.react;

import com.pulseink.agent.api.AgentEngine;
import com.pulseink.agent.api.AgentExecutionObserver;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.domain.execution.ExecutionMode;
import java.util.Objects;

/**
 * Unified single-agent engine: a strong REACT baseline over a single controlled loop. It shares
 * the exact {@code AgentArtifact} schema with the future orchestrated runtime.
 */
public final class UnifiedAgentRunner implements AgentEngine {

    private final ReactLoop reactLoop;

    public UnifiedAgentRunner(ReactLoop reactLoop) {
        this.reactLoop = Objects.requireNonNull(reactLoop, "reactLoop must not be null");
    }

    @Override
    public ExecutionMode supportedMode() {
        return ExecutionMode.REACT;
    }

    @Override
    public AgentExecutionResult execute(
            AgentExecutionRequest request,
            AgentExecutionObserver observer) {
        return reactLoop.execute(request, observer);
    }
}
