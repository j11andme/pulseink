package com.pulseink.service.campaign;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable persisted run event. Every payload carries {@code eventVersion=run-event-v1}.
 */
public record RunEvent(
        long runId,
        long sequence,
        RunEventType type,
        Map<String, Object> payload,
        Instant createdAt) {

    public static final String EVENT_VERSION = "run-event-v1";

    public RunEvent {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        type = Objects.requireNonNull(type, "type must not be null");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
