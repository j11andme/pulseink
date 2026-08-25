package com.pulseink.domain.memory;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One auditable source reference of an insight: the approved content version, the published
 * post and the metric window the conclusion was derived from.
 */
public record InsightEvidenceRef(
        long contentVersionId,
        long publicationId,
        LocalDate metricFrom,
        LocalDate metricTo) {

    public InsightEvidenceRef {
        if (contentVersionId <= 0) {
            throw new IllegalArgumentException("evidence contentVersionId must be positive");
        }
        if (publicationId <= 0) {
            throw new IllegalArgumentException("evidence publicationId must be positive");
        }
        Objects.requireNonNull(metricFrom, "evidence metricFrom must not be null");
        Objects.requireNonNull(metricTo, "evidence metricTo must not be null");
        if (metricFrom.isAfter(metricTo)) {
            throw new IllegalArgumentException("evidence metric window must not be inverted");
        }
    }
}
