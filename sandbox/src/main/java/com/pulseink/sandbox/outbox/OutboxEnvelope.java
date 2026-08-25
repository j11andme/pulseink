package com.pulseink.sandbox.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * One outbox row after a claim. {@code payloadJson} is the serialized Kafka Feedback Event V1
 * envelope and {@code aggregateId} is the external post id used as the Kafka key.
 */
public record OutboxEnvelope(
        long id,
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        int schemaVersion,
        String payloadJson,
        String status,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant createdAt,
        Instant publishedAt) {
}
