package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.knowledge.KnowledgeSearchService.SearchResult;
import java.time.Instant;
import java.util.List;

/**
 * Read-side entry points for knowledge documents and hybrid search-test.
 */
public interface QueryKnowledgeUseCase {

    DocumentPage list(KnowledgeDocumentStatus status, KnowledgeType type,
                      int page, int size);

    SearchResult search(String query, List<KnowledgeType> types,
                        List<EvidenceAuthority> authorities,
                        Instant updatedAfter, int topK);

    record DocumentPage(
            long total,
            List<DocumentItem> items) {
    }

    record DocumentItem(
            long documentId,
            String sourceId,
            String originalFilename,
            String declaredMimeType,
            String detectedMimeType,
            long sizeBytes,
            KnowledgeType knowledgeType,
            EvidenceAuthority authority,
            int documentVersion,
            KnowledgeDocumentStatus status,
            int chunkCount,
            String failureCode,
            Instant createdAt,
            Instant updatedAt) {
    }
}
