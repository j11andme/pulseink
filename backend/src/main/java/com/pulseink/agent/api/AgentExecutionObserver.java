package com.pulseink.agent.api;

import com.pulseink.agent.artifact.AgentArtifact;
import java.util.List;

/**
 * Functional observer that receives {@link AgentRuntimeEvent}s during execution. The engine
 * calls {@link #onEvent} synchronously; the observer is responsible for any persistence.
 */
@FunctionalInterface
public interface AgentExecutionObserver {
    void onEvent(AgentRuntimeEvent event);
}
