package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeType;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable document-level index metadata.
 */
public record IndexedDocument(
        long documentId,
        int documentVersion,
        String sourceId,
        String title,
        KnowledgeType knowledgeType,
        EvidenceAuthority authority,
        Instant updatedAt) {

    public IndexedDocument {
        if (documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
        if (documentVersion <= 0) {
            throw new IllegalArgumentException("documentVersion must be positive");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        knowledgeType = Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        authority = Objects.requireNonNull(authority, "authority must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
