package com.pulseink.domain.knowledge;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable evidence presented to an agent or search-test consumer. Never carries vectors,
 * storage keys, absolute paths or full text; the snippet is a controlled-length summary.
 */
public record EvidenceChunk(
        String sourceId,
        long documentId,
        int documentVersion,
        String chunkId,
        String title,
        String headingPath,
        String snippet,
        double score,
        Set<String> channels,
        KnowledgeType knowledgeType,
        EvidenceAuthority authority,
        Instant updatedAt) {

    public EvidenceChunk {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (documentId <= 0) {
            throw new IllegalArgumentException("documentId must be positive");
        }
        if (documentVersion <= 0) {
            throw new IllegalArgumentException("documentVersion must be positive");
        }
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
        if (snippet == null || snippet.isBlank()) {
            throw new IllegalArgumentException("snippet must not be blank");
        }
        if (Double.isNaN(score) || Double.isInfinite(score) || score < 0) {
            throw new IllegalArgumentException("score must be a finite non-negative number");
        }
        channels = Set.copyOf(Objects.requireNonNull(channels, "channels must not be null"));
        knowledgeType = Objects.requireNonNull(knowledgeType, "knowledgeType must not be null");
        authority = Objects.requireNonNull(authority, "authority must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
