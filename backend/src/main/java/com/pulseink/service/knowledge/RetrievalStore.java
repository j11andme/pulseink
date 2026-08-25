package com.pulseink.service.knowledge;

import com.pulseink.service.embedding.EmbeddingProfile;
import java.util.List;

/**
 * Business-neutral retrieval port backed by Elasticsearch physical indices behind one active
 * alias. Callers never depend on the ES client.
 */
public interface RetrievalStore {

    void ensureCompatibleIndex(EmbeddingProfile profile);

    String physicalIndexName(EmbeddingProfile profile);

    void replaceDocumentVersion(IndexedDocument document, List<EmbeddedChunk> chunks);

    RetrievalCandidates search(HybridSearchQuery query);

    void deleteDocumentVersion(long documentId, int documentVersion);
}
