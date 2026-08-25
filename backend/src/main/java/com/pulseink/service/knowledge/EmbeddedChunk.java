package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeType;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable embedded chunk with its vector. The vector is defensively copied.
 */
public record EmbeddedChunk(
        String chunkId,
        long documentId,
        int documentVersion,
        String sourceId,
        int ordinal,
        String title,
        String headingPath,
        String text,
        KnowledgeType knowledgeType,
        EvidenceAuthority authority,
        String documentStatus,
        String embeddingProfileId,
        Instant updatedAt,
        float[] vector) {

    public EmbeddedChunk {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
        if (documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
        if (documentVersion <= 0) {
            throw new IllegalArgumentException("documentVersion must be positive");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("chunk text must not be blank");
        }
        knowledgeType = Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        authority = Objects.requireNonNull(authority, "authority must not be null");
        vector = vector == null ? new float[0] : vector.clone();
        if (vector.length == 0) {
            throw new IllegalArgumentException("vector must not be empty");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("vector contains non-finite value");
            }
        }
    }

    public float[] vectorCopy() {
        return vector.clone();
    }
}
