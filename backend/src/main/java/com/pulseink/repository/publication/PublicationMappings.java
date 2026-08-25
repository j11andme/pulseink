package com.pulseink.repository.publication;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.publication.Publication;
import com.pulseink.domain.publication.PublicationStatus;
import java.util.UUID;

/**
 * Shared entity/domain mapping for the publication row so both the publishing and the feedback
 * persistence adapters read the same row through one conversion path.
 */
public final class PublicationMappings {

    private PublicationMappings() {
    }

    public static PublicationEntity toEntity(Publication publication) {
        var entity = new PublicationEntity();
        entity.setId(publication.id() > 0 ? publication.id() : null);
        entity.setRunId(publication.runId());
        entity.setContentVersionId(publication.contentVersionId());
        entity.setApprovalRecordId(publication.approvalRecordId());
        entity.setRequestedBy(publication.requestedBy());
        entity.setChannel(publication.channel().name());
        entity.setIdempotencyKey(publication.idempotencyKey().toString());
        entity.setStatus(publication.status().name());
        entity.setAttemptCount(publication.attemptCount());
        entity.setNextAttemptAt(publication.nextAttemptAt());
        entity.setVersion(publication.version());
        entity.setExternalPostId(publication.externalPostId() == null
                ? null : publication.externalPostId().toString());
        entity.setReceiptJson(publication.receiptJson());
        entity.setFailureCode(publication.failureCode());
        entity.setFailureMessage(publication.failureMessage());
        entity.setPublishedAt(publication.publishedAt());
        return entity;
    }

    public static Publication toDomain(PublicationEntity entity) {
        return new Publication(
                entity.getId(),
                entity.getRunId(),
                entity.getContentVersionId(),
                entity.getApprovalRecordId(),
                entity.getRequestedBy(),
                toChannel(entity.getChannel()),
                UUID.fromString(entity.getIdempotencyKey()),
                toStatus(entity.getStatus()),
                entity.getAttemptCount() == null ? 0 : entity.getAttemptCount(),
                entity.getNextAttemptAt(),
                entity.getVersion() == null ? 0L : entity.getVersion(),
                entity.getExternalPostId() == null ? null : UUID.fromString(entity.getExternalPostId()),
                entity.getReceiptJson(),
                entity.getFailureCode(),
                entity.getFailureMessage(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPublishedAt());
    }

    private static CampaignChannel toChannel(String value) {
        try {
            return CampaignChannel.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "publication stored an unknown channel value: " + value, ex);
        }
    }

    private static PublicationStatus toStatus(String value) {
        try {
            return PublicationStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "publication stored an unknown status value: " + value, ex);
        }
    }
}
