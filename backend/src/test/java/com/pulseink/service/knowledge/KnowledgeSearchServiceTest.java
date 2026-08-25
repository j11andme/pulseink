package com.pulseink.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeDocument;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.embedding.EmbeddingProfile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    private final FakeRetrievalStore store = new FakeRetrievalStore();
    private final FakeDocumentRepository documents = new FakeDocumentRepository();
    private final KnowledgeSearchService service =
            new KnowledgeSearchService(store, documents, 60, 500);

    private HybridSearchQuery query() {
        return new HybridSearchQuery("brand color", List.of(), List.of(), null, 5, 10,
                EmbeddingProfile.of("fake", "m", 64));
    }

    @Test
    void fusesAndKeepsOnlyActiveDocumentsWithMatchingVersion() {
        store.candidates = new RetrievalCandidates(
                RetrievalMode.HYBRID, null,
                List.of(candidate("c1", 1L, 2)), List.of());
        documents.addActive(1L, 2, "c1-source");

        var result = service.search(query());

        assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(result.degradedReasonCode()).isNull();
        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).sourceId()).isEqualTo("c1-src");
        assertThat(result.evidence().get(0).channels()).contains("LEXICAL");
    }

    @Test
    void ghostAndVersionMismatchDocumentsAreFilteredOut() {
        store.candidates = new RetrievalCandidates(
                RetrievalMode.HYBRID, null,
                List.of(candidate("c1", 1L, 2), candidate("c2", 2L, 1), candidate("c3", 3L, 1)),
                List.of());
        documents.addActive(1L, 1, "c1-source"); // version mismatch: es says 2
        documents.addActive(3L, 1, "c3-source"); // c2 is a ghost

        var result = service.search(query());

        assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().get(0).sourceId()).isEqualTo("c3-src");
    }

    @Test
    void lexicalFallbackModeIsPassedThrough() {
        store.candidates = new RetrievalCandidates(
                RetrievalMode.LEXICAL_FALLBACK, "EMBEDDING_PROVIDER_FAILED",
                List.of(candidate("c1", 1L, 1)), List.of());
        documents.addActive(1L, 1, "c1-source");

        var result = service.search(query());

        assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.LEXICAL_FALLBACK);
        assertThat(result.degradedReasonCode()).isEqualTo("EMBEDDING_PROVIDER_FAILED");
        assertThat(result.evidence()).hasSize(1);
    }

    @Test
    void emptyKnowledgeBaseReturnsHybridWithNoEvidence() {
        store.candidates = new RetrievalCandidates(RetrievalMode.HYBRID, null, List.of(), List.of());
        var result = service.search(query());
        assertThat(result.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void indexErrorsAreNotDegradedToEmpty() {
        store.indexFailure = new RuntimeException("index unavailable");
        assertThatThrownBy(() -> service.search(query()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void snippetIsLimitedToMaxCodePoints() {
        store.candidates = new RetrievalCandidates(
                RetrievalMode.HYBRID, null,
                List.of(candidate("c1", 1L, 1)), List.of());
        documents.addActive(1L, 1, "c1-source");
        store.chunkTexts.put("c1", "x".repeat(2000));

        var result = service.search(query());
        assertThat(result.evidence().get(0).snippet().codePointCount(0,
                result.evidence().get(0).snippet().length())).isLessThanOrEqualTo(500);
    }

    private static RetrievalCandidates.Candidate candidate(String chunkId, long docId, int version) {
        return new RetrievalCandidates.Candidate(
                chunkId, docId, version, 1.0, "title", "path", "text", chunkId + "-src",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, NOW);
    }

    static final class FakeRetrievalStore implements RetrievalStore {
        RetrievalCandidates candidates;
        RuntimeException indexFailure;
        final java.util.Map<String, String> chunkTexts = new java.util.HashMap<>();

        @Override
        public void ensureCompatibleIndex(EmbeddingProfile profile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String physicalIndexName(EmbeddingProfile profile) {
            return "test-index";
        }

        @Override
        public void replaceDocumentVersion(IndexedDocument document, List<EmbeddedChunk> chunks) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RetrievalCandidates search(HybridSearchQuery query) {
            if (indexFailure != null) {
                throw indexFailure;
            }
            return candidates;
        }

        @Override
        public void deleteDocumentVersion(long documentId, int documentVersion) {
            throw new UnsupportedOperationException();
        }
    }

    static final class FakeDocumentRepository implements KnowledgeDocumentRepository {
        final java.util.Map<Long, KnowledgeDocument> active = new java.util.HashMap<>();

        void addActive(long id, int version, String sourceId) {
            active.put(id, KnowledgeDocument.materialize(
                    id, sourceId, "f.md", "key", "text/markdown", null, 1L, "sha",
                    KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, version,
                    KnowledgeDocumentStatus.ACTIVE, "p", "idx", 1, null, 1L, 0L,
                    NOW, NOW));
        }

        @Override
        public KnowledgeDocument insert(KnowledgeDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeDocument> findById(long id) {
            return Optional.empty();
        }

        @Override
        public Optional<KnowledgeDocument> findByChecksumAndType(String checksumSha256,
                                                                 KnowledgeType knowledgeType) {
            return Optional.empty();
        }

        @Override
        public void update(KnowledgeDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markProcessing(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markActive(long id, String detectedMimeType, String embeddingProfileId,
                               String indexName, int chunkCount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void markFailed(long id, String failureCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retry(long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DocumentPage findPage(KnowledgeDocumentStatus status, KnowledgeType type,
                                     int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<KnowledgeDocument> findActiveByIds(List<Long> ids) {
            return ids.stream().map(active::get).filter(java.util.Objects::nonNull).toList();
        }
    }
}
