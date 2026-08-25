package com.pulseink.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IngestionJobTest {

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    private IngestionJob newJob() {
        return IngestionJob.create("job-1", 5L);
    }

    @Test
    void createProducesPendingJobWithZeroAttempt() {
        var job = newJob();
        assertThat(job.jobId()).isEqualTo("job-1");
        assertThat(job.documentId()).isEqualTo(5L);
        assertThat(job.status()).isEqualTo(IngestionJobStatus.PENDING);
        assertThat(job.attempt()).isZero();
        assertThat(job.startedAt()).isNull();
        assertThat(job.completedAt()).isNull();
        assertThat(job.version()).isZero();
    }

    @Test
    void startProcessingIncrementsAttempt() {
        var job = newJob();
        job.startProcessing(NOW);
        assertThat(job.status()).isEqualTo(IngestionJobStatus.PROCESSING);
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(job.startedAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> job.startProcessing(NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(job.attempt()).isEqualTo(1);
    }

    @Test
    void succeededAndFailedAreOnlyFromProcessing() {
        var job = newJob();
        assertThatThrownBy(() -> job.markSucceeded(NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> job.markFailed("X", NOW))
                .isInstanceOf(IllegalStateException.class);

        job.startProcessing(NOW);
        job.markSucceeded(NOW.plusSeconds(5));
        assertThat(job.status()).isEqualTo(IngestionJobStatus.SUCCEEDED);
        assertThat(job.completedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void failedJobCanBeRetriedToPending() {
        var job = newJob();
        job.startProcessing(NOW);
        job.markFailed("EMBEDDING_PROVIDER_FAILED", NOW);
        assertThat(job.failureCode()).isEqualTo("EMBEDDING_PROVIDER_FAILED");

        job.retry();
        assertThat(job.status()).isEqualTo(IngestionJobStatus.PENDING);
        assertThat(job.failureCode()).isNull();
        assertThat(job.attempt()).isEqualTo(1);
    }

    @Test
    void recoverablePendingJobIsAlwaysEligible() {
        var job = newJob();
        assertThat(job.isRecoverable(NOW, java.time.Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void recoverableProcessingJobExpiresAfterTimeout() {
        var job = newJob();
        job.startProcessing(NOW);
        assertThat(job.isRecoverable(NOW, java.time.Duration.ofMinutes(10))).isFalse();
        assertThat(job.isRecoverable(NOW.plusSeconds(601), java.time.Duration.ofMinutes(10)))
                .isTrue();
    }

    @Test
    void staleProcessingJobCanBeExplicitlyReclaimed() {
        var job = newJob();
        job.startProcessing(NOW);

        job.reclaimProcessing(NOW.plusSeconds(601));

        assertThat(job.status()).isEqualTo(IngestionJobStatus.PROCESSING);
        assertThat(job.attempt()).isEqualTo(2);
        assertThat(job.startedAt()).isEqualTo(NOW.plusSeconds(601));
    }

    @Test
    void terminalJobsAreNeverRecoverable() {
        var succeeded = newJob();
        succeeded.startProcessing(NOW);
        succeeded.markSucceeded(NOW);
        assertThat(succeeded.isRecoverable(NOW, java.time.Duration.ofMinutes(10))).isFalse();

        var failed = newJob();
        failed.startProcessing(NOW);
        failed.markFailed("X", NOW);
        assertThat(failed.isRecoverable(NOW, java.time.Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void materializeRestoresFullState() {
        var job = IngestionJob.materialize(
                1L, "job-9", 5L, IngestionJobStatus.PROCESSING, 3,
                "EMBEDDING_DIMENSION_MISMATCH", NOW, NOW, 2L, NOW, NOW);
        assertThat(job.id()).isEqualTo(1L);
        assertThat(job.jobId()).isEqualTo("job-9");
        assertThat(job.attempt()).isEqualTo(3);
        assertThat(job.failureCode()).isEqualTo("EMBEDDING_DIMENSION_MISMATCH");
        assertThat(job.version()).isEqualTo(2L);
    }

    @Test
    void rejectsBlankJobIdAndNonPositiveDocument() {
        assertThatThrownBy(() -> IngestionJob.create("", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IngestionJob.create("job", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
