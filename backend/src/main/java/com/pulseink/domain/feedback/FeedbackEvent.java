package com.pulseink.domain.feedback;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Validated snapshot of one Kafka Feedback Event V1. The backend only accepts schema version 1
 * with non-negative counters and a metricDate consistent with the business time zone.
 */
public record FeedbackEvent(
        int schemaVersion,
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID externalPostId,
        long publicationId,
        long contentVersionId,
        String channel,
        LocalDate metricDate,
        long views,
        long clicks,
        long likes) {

    public FeedbackEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(externalPostId, "externalPostId must not be null");
        Objects.requireNonNull(metricDate, "metricDate must not be null");
    }
}
