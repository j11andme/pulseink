package com.pulseink.domain.publication;

import com.pulseink.domain.campaign.CampaignChannel;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Publication snapshot for one approved {@code ContentVersion} on one channel. The idempotency
 * key is generated once by the backend and is the authority for deduplication on the sandbox
 * side; {@code version} guards every CAS state transition of the row.
 */
public record Publication(
        long id,
        long runId,
        long contentVersionId,
        long approvalRecordId,
        long requestedBy,
        CampaignChannel channel,
        UUID idempotencyKey,
        PublicationStatus status,
        int attemptCount,
        Instant nextAttemptAt,
        long version,
        UUID externalPostId,
        String receiptJson,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt) {

    public Publication {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotency key must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public static Publication pending(
            long runId,
            long contentVersionId,
            long approvalRecordId,
            long requestedBy,
            CampaignChannel channel,
            UUID idempotencyKey,
            Instant createdAt) {
        return new Publication(0L, runId, contentVersionId, approvalRecordId, requestedBy,
                channel, idempotencyKey, PublicationStatus.PENDING, 0, null, 0L,
                null, null, null, null, createdAt, null, null);
    }
}
