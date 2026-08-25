package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.embedding.EmbeddingProfile;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable hybrid search query. topK must be 1..10 and branchLimit topK..100; the query text
 * must be non-blank and within a sane length.
 */
public record HybridSearchQuery(
        String query,
        List<KnowledgeType> knowledgeTypes,
        List<EvidenceAuthority> authorities,
        Instant updatedAfter,
        int topK,
        int branchLimit,
        EmbeddingProfile profile) {

    public HybridSearchQuery {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (query.length() > 2000) {
            throw new IllegalArgumentException("query must contain at most 2000 characters");
        }
        knowledgeTypes = knowledgeTypes == null ? List.of() : List.copyOf(knowledgeTypes);
        authorities = authorities == null ? List.of() : List.copyOf(authorities);
        if (topK < 1 || topK > 10) {
            throw new IllegalArgumentException("topK must be between 1 and 10");
        }
        if (branchLimit < topK || branchLimit > 100) {
            throw new IllegalArgumentException("branchLimit must be between topK and 100");
        }
        profile = Objects.requireNonNull(profile, "profile must not be null");
    }
}
