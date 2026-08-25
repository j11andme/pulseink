package com.pulseink.agent.model;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * Functional model port. {@link #stream} keeps the streaming playground contract; {@link #complete}
 * is the blocking completion call used by the agent runtime and is provided by the collector.
 */
@FunctionalInterface
public interface AgentModelPort {

    ModelStreamHandle stream(
            ModelRequest request,
            Consumer<ModelStreamEvent> eventConsumer);

    default ModelCompletion complete(
            ModelRequest request,
            Duration timeout) {
        return ModelCompletionCollector.collect(this, request, timeout);
    }
}
