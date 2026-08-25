package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.EvidenceChunk;
import com.pulseink.domain.knowledge.KnowledgeDocument;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Hybrid search orchestration: RRF fusion in Java, then a single batched MySQL ACTIVE check so
 * Elasticsearch ghosts and stale versions never reach the agent. Snippets are truncated to a
 * controlled length; full text and vectors never leave the service.
 */
public class KnowledgeSearchService {

    private final RetrievalStore store;
    private final KnowledgeDocumentRepository documents;
    private final int rrfConstant;
    private final int snippetMaxCodePoints;
    private final RrfFusion fusion = new RrfFusion();

    public KnowledgeSearchService(RetrievalStore store,
                                  KnowledgeDocumentRepository documents,
                                  int rrfConstant,
                                  int snippetMaxCodePoints) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
        if (rrfConstant <= 0) {
            throw new IllegalArgumentException("rrfConstant must be positive");
        }
        if (snippetMaxCodePoints <= 0) {
            throw new IllegalArgumentException("snippetMaxCodePoints must be positive");
        }
        this.rrfConstant = rrfConstant;
        this.snippetMaxCodePoints = snippetMaxCodePoints;
    }

    public SearchResult search(HybridSearchQuery query) {
        var candidates = store.search(query);
        var fused = fusion.fuse(
                candidates.lexical(), candidates.vector(), query.topK(), rrfConstant);
        if (fused.isEmpty()) {
            return new SearchResult(candidates.mode(), candidates.degradedReasonCode(), List.of());
        }

        var ids = new ArrayList<Long>();
        for (var result : fused) {
            if (!ids.contains(result.documentId())) {
                ids.add(result.documentId());
            }
        }
        var active = new HashMap<Long, KnowledgeDocument>();
        for (var document : documents.findActiveByIds(ids)) {
            active.put(document.id(), document);
        }

        var byChunk = candidatesByChunk(candidates);
        var evidence = new ArrayList<EvidenceChunk>();
        for (var result : fused) {
            var document = active.get(result.documentId());
            if (document == null
                    || document.status() != KnowledgeDocumentStatus.ACTIVE
                    || document.documentVersion() != result.documentVersion()) {
                continue;
            }
            var candidate = byChunk.get(result.chunkId());
            if (candidate == null) {
                continue;
            }
            evidence.add(new EvidenceChunk(
                    candidate.sourceId(),
                    result.documentId(),
                    result.documentVersion(),
                    result.chunkId(),
                    candidate.title(),
                    candidate.headingPath(),
                    truncate(candidate.text(), snippetMaxCodePoints),
                    result.rrfScore(),
                    result.channels(),
                    candidate.knowledgeType(),
                    candidate.authority(),
                    candidate.updatedAt()));
        }
        return new SearchResult(
                candidates.mode(), candidates.degradedReasonCode(), List.copyOf(evidence));
    }

    private static Map<String, RetrievalCandidates.Candidate> candidatesByChunk(
            RetrievalCandidates candidates) {
        var map = new HashMap<String, RetrievalCandidates.Candidate>();
        for (var candidate : candidates.lexical()) {
            map.putIfAbsent(candidate.chunkId(), candidate);
        }
        for (var candidate : candidates.vector()) {
            map.putIfAbsent(candidate.chunkId(), candidate);
        }
        return map;
    }

    private static String truncate(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, end) + "...";
    }

    /**
     * Final evidence result: mode, optional degradation reason and immutable evidence chunks.
     */
    public record SearchResult(
            RetrievalMode retrievalMode,
            String degradedReasonCode,
            List<EvidenceChunk> evidence) {
    }
}
