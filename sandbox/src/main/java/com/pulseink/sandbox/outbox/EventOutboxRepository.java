package com.pulseink.sandbox.outbox;

import com.pulseink.sandbox.domain.FeedbackEvent;
import java.time.Instant;
import java.util.List;

/**
 * Transactional outbox store. Rows move PENDING/RETRY_WAIT to SENDING under a CAS claim and
 * become claimable again when a claimed send never reached a terminal outcome (crash window).
 */
public interface EventOutboxRepository {

    void insert(FeedbackEvent event);

    List<OutboxEnvelope> claimDue(Instant now, int batchSize);

    void markPublished(long id);

    void markRetryWait(long id, Instant nextAttemptAt, String error);

    void markDead(long id, String error);
}
