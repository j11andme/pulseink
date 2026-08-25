package com.pulseink.agent.model;

public sealed interface ModelStreamEvent
        permits ModelStreamEvent.Started,
                ModelStreamEvent.ContentDelta,
                ModelStreamEvent.Completed,
                ModelStreamEvent.Failed,
                ModelStreamEvent.Usage {

    String requestId();

    record Started(
            String requestId,
            String provider,
            String model) implements ModelStreamEvent {}

    record ContentDelta(
            String requestId,
            String content) implements ModelStreamEvent {}

    record Completed(
            String requestId,
            String finishReason) implements ModelStreamEvent {}

    record Failed(
            String requestId,
            String code,
            String message) implements ModelStreamEvent {}

    record Usage(
            String requestId,
            long inputTokens,
            long outputTokens) implements ModelStreamEvent {}
}
