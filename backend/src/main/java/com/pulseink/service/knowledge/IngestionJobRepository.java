package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.IngestionJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for ingestion jobs. A job is bound 1:1 to a knowledge document.
 */
public interface IngestionJobRepository {

    IngestionJob insert(IngestionJob job);

    Optional<IngestionJob> findById(long id);

    Optional<IngestionJob> findByDocumentId(long documentId);

    void update(IngestionJob job);

    void startProcessing(long id, Instant startedAt);

    void markSucceeded(long id, Instant completedAt);

    void markFailed(long id, String failureCode, Instant completedAt);

    void retry(long id);

    List<IngestionJob> findRecoverable(int limit, Duration staleTimeout);

    List<IngestionJob> findPending(int limit);
}
