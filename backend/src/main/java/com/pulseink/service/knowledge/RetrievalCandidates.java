package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeType;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Two ordered, de-duplicated candidate lists from the retrieval store; RRF fusion happens in
 * Java, never inside Elasticsearch.
 */
public record RetrievalCandidates(
        RetrievalMode mode,
        String degradedReasonCode,
        List<Candidate> lexical,
        List<Candidate> vector) {

    public RetrievalCandidates {
        mode = Objects.requireNonNull(mode, "mode must not be null");
        lexical = lexical == null ? List.of() : List.copyOf(lexical);
        vector = vector == null ? List.of() : List.copyOf(vector);
    }

    public record Candidate(
            String chunkId,
            long documentId,
            int documentVersion,
            double score,
            String title,
            String headingPath,
            String text,
            String sourceId,
            KnowledgeType knowledgeType,
            EvidenceAuthority authority,
            Instant updatedAt) {

        public Candidate {
            if (chunkId == null || chunkId.isBlank()) {
                throw new IllegalArgumentException("chunkId must not be blank");
            }
            if (documentId <= 0) {
                throw new IllegalArgumentException("documentId must be positive");
            }
            if (documentVersion <= 0) {
                throw new IllegalArgumentException("documentVersion must be positive");
            }
            if (Double.isNaN(score) || Double.isInfinite(score)) {
                throw new IllegalArgumentException("score must be finite");
            }
        }
    }
}
