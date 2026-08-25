package com.pulseink.domain.feedback;

import java.time.LocalDate;

/**
 * Aggregated daily metrics for one publication. Rows are updated idempotently per eventId so a
 * duplicated Kafka delivery never accumulates twice.
 */
public record ContentMetricDaily(
        long publicationId,
        LocalDate metricDate,
        long views,
        long clicks,
        long likes) {
}
