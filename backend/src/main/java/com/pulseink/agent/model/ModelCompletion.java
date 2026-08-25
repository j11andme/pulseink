package com.pulseink.agent.model;

import java.util.Objects;

/**
 * Immutable aggregate of one blocking model completion call.
 */
public record ModelCompletion(
        String requestId,
        String providerId,
        String modelId,
        String content,
        long inputTokens,
        long outputTokens,
        String finishReason) {

    public ModelCompletion {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");
    }
}
