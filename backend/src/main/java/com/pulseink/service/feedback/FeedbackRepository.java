package com.pulseink.service.feedback;

import com.pulseink.domain.feedback.ContentMetricDaily;
import com.pulseink.domain.feedback.FeedbackEvent;
import java.util.List;

/**
 * Persistence port for feedback ingestion. {@link #ingest} inserts the inbox row and applies the
 * daily metric upsert in one transaction; a duplicated eventId returns false without touching
 * metrics.
 */
public interface FeedbackRepository {

    /**
     * Inbox insert-if-absent plus daily metric accumulation in the same transaction.
     *
     * @return true when the event was new and its deltas were applied; false for a duplicate
     */
    boolean ingest(FeedbackEvent event, String sourceTopic, int sourcePartition, long sourceOffset);

    /**
     * Reference to the publication the event belongs to; used to reject events for unknown
     * publications and to resolve run scoped queries.
     */
    PublicationRef findByPublicationId(long publicationId);

    List<ContentMetricDaily> findByRunId(long runId);

    record PublicationRef(long id, long runId) {}
}
