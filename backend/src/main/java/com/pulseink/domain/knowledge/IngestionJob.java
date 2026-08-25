package com.pulseink.domain.knowledge;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable ingestion job bound 1:1 to a {@link KnowledgeDocument}. Attempts increase on every
 * PROCESSING start; PENDING jobs and PROCESSING jobs past the stale timeout are recoverable.
 */
public final class IngestionJob {

    private final long id;
    private final String jobId;
    private final long documentId;
    private IngestionJobStatus status;
    private int attempt;
    private String failureCode;
    private Instant startedAt;
    private Instant completedAt;
    private long version;
    private final Instant createdAt;
    private Instant updatedAt;

    private IngestionJob(long id, String jobId, long documentId,
                         IngestionJobStatus status, int attempt, String failureCode,
                         Instant startedAt, Instant completedAt, long version,
                         Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.jobId = requireNonBlank(jobId, "jobId");
        if (documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
        this.documentId = documentId;
        this.status = Objects.requireNonNull(status, "status must not be null");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        this.attempt = attempt;
        this.failureCode = failureCode;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static IngestionJob create(String jobId, long documentId) {
        var now = Instant.now();
        return new IngestionJob(
                0L, jobId, documentId, IngestionJobStatus.PENDING, 0, null,
                null, null, 0L, now, now);
    }

    public static IngestionJob materialize(
            long id, String jobId, long documentId, IngestionJobStatus status,
            int attempt, String failureCode, Instant startedAt, Instant completedAt,
            long version, Instant createdAt, Instant updatedAt) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        return new IngestionJob(id, jobId, documentId, status, attempt, failureCode,
                startedAt, completedAt, version, createdAt, updatedAt);
    }

    public void startProcessing(Instant now) {
        requireStatus(IngestionJobStatus.PENDING, "only PENDING jobs can start processing");
        this.status = IngestionJobStatus.PROCESSING;
        this.attempt++;
        this.startedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void reclaimProcessing(Instant now) {
        requireStatus(IngestionJobStatus.PROCESSING,
                "only PROCESSING jobs can be reclaimed");
        this.attempt++;
        this.startedAt = Objects.requireNonNull(now, "now must not be null");
        this.completedAt = null;
        this.failureCode = null;
    }

    public void markSucceeded(Instant now) {
        requireStatus(IngestionJobStatus.PROCESSING, "only PROCESSING jobs can succeed");
        this.completedAt = Objects.requireNonNull(now, "now must not be null");
        this.status = IngestionJobStatus.SUCCEEDED;
    }

    public void markFailed(String failureCode, Instant now) {
        requireStatus(IngestionJobStatus.PROCESSING, "only PROCESSING jobs can fail");
        this.failureCode = requireNonBlank(failureCode, "failureCode");
        this.completedAt = Objects.requireNonNull(now, "now must not be null");
        this.status = IngestionJobStatus.FAILED;
    }

    public void retry() {
        requireStatus(IngestionJobStatus.FAILED, "only FAILED jobs can be retried");
        this.failureCode = null;
        this.status = IngestionJobStatus.PENDING;
    }

    public boolean isRecoverable(Instant now, Duration staleTimeout) {
        return switch (status) {
            case PENDING -> true;
            case PROCESSING -> startedAt != null
                    && startedAt.plus(staleTimeout).isBefore(Objects.requireNonNull(now));
            case SUCCEEDED, FAILED -> false;
        };
    }

    private void requireStatus(IngestionJobStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message + ", was " + status);
        }
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    public long id() { return id; }
    public String jobId() { return jobId; }
    public long documentId() { return documentId; }
    public IngestionJobStatus status() { return status; }
    public int attempt() { return attempt; }
    public String failureCode() { return failureCode; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
