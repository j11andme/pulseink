package com.pulseink.client.kafka;

import com.pulseink.domain.feedback.FeedbackEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Kafka Feedback Event V1 wire shape owned by the backend. Parsed strictly in the consumer;
 * the sandbox owns an identical DTO and both applications verify the same JSON fixture.
 */
public record FeedbackEventMessage(
        int schemaVersion,
        String eventId,
        String eventType,
        String occurredAt,
        String externalPostId,
        long publicationId,
        long contentVersionId,
        String channel,
        String metricDate,
        Deltas deltas) {

    public record Deltas(long views, long clicks, long likes) {}

    public FeedbackEvent toDomain() {
        return new FeedbackEvent(
                schemaVersion,
                UUID.fromString(eventId),
                eventType,
                Instant.parse(occurredAt),
                UUID.fromString(externalPostId),
                publicationId,
                contentVersionId,
                channel,
                LocalDate.parse(metricDate),
                deltas.views(),
                deltas.clicks(),
                deltas.likes());
    }
}
