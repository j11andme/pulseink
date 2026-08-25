package com.pulseink.sandbox.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Channel metrics event that the sandbox records into its outbox in the same transaction that
 * creates the channel post. Serialized as Kafka Feedback Event V1 by the outbox repository.
 */
public record FeedbackEvent(
        UUID eventId,
        String eventType,
        UUID externalPostId,
        long publicationId,
        long contentVersionId,
        String channel,
        Instant occurredAt,
        LocalDate metricDate,
        long views,
        long clicks,
        long likes) {

    public FeedbackEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(externalPostId, "externalPostId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(metricDate, "metricDate must not be null");
    }

    public static FeedbackEvent recorded(
            UUID eventId,
            UUID externalPostId,
            long publicationId,
            long contentVersionId,
            String channel,
            Instant occurredAt,
            LocalDate metricDate,
            long views,
            long clicks,
            long likes) {
        return new FeedbackEvent(eventId, "CHANNEL_METRICS_RECORDED", externalPostId,
                publicationId, contentVersionId, channel, occurredAt, metricDate,
                views, clicks, likes);
    }
}
