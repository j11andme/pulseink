package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.KnowledgeDocument;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for knowledge documents. MySQL is the authoritative store.
 */
public interface KnowledgeDocumentRepository {

    KnowledgeDocument insert(KnowledgeDocument document);

    Optional<KnowledgeDocument> findById(long id);

    Optional<KnowledgeDocument> findByChecksumAndType(String checksumSha256,
                                                      KnowledgeType knowledgeType);

    void update(KnowledgeDocument document);

    void markProcessing(long id);

    void markActive(long id, String detectedMimeType, String embeddingProfileId,
                    String indexName, int chunkCount);

    void markFailed(long id, String failureCode);

    void retry(long id);

    DocumentPage findPage(KnowledgeDocumentStatus status, KnowledgeType type,
                          int page, int size);

    List<KnowledgeDocument> findActiveByIds(List<Long> ids);

    record DocumentPage(long total, List<KnowledgeDocument> items) {
    }
}
