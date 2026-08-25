package com.pulseink.service.publishing;

import com.pulseink.domain.publication.Publication;
import com.pulseink.domain.publication.PublishReceipt;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the publication workflow. Every mutating call runs in its own short
 * transaction; the Channel HTTP call happens strictly outside these transactions.
 */
public interface PublicationRepository {

    /**
     * Idempotently creates a PENDING publication. A second call for the same
     * {@code contentVersionId + channel} returns the existing row with its original key.
     */
    Publication createOrGet(Publication pending);

    Optional<Publication> findById(long publicationId);

    List<Publication> findByRunId(long runId);

    /**
     * Claims up to {@code batchSize} due tasks with a CAS transition to SENDING; stuck SENDING
     * rows whose visibility deadline has passed become claimable again.
     */
    List<Publication> claimDue(Instant now, int batchSize);

    /** CAS transition PENDING/RETRY_WAIT to SENDING; false when the version no longer matches. */
    boolean claim(long publicationId, long expectedVersion);

    boolean markPublished(long publicationId, long expectedVersion, PublishReceipt receipt);

    boolean markRetryWait(long publicationId, long expectedVersion,
                          Instant nextAttemptAt, String failureCode, String failureMessage);

    boolean markFailed(long publicationId, long expectedVersion,
                       String failureCode, String failureMessage);
}
