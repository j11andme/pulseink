package com.pulseink.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.IngestionJob;
import com.pulseink.domain.knowledge.IngestionJobStatus;
import com.pulseink.domain.knowledge.KnowledgeDocument;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.embedding.EmbeddingPort.EmbeddingException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeIngestionCoordinatorTest {

    private final FakeDocuments documents = new FakeDocuments();
    private final FakeJobs jobs = new FakeJobs();
    private final FakeStore store = new FakeStore();
    private final FakeExtractor extractor = new FakeExtractor();
    private final FakeEmbedding embedding = new FakeEmbedding();
    private final FakeRetrieval retrieval = new FakeRetrieval();
    private final KnowledgeIngestionCoordinator.Default coordinator =
            new KnowledgeIngestionCoordinator.Default(
                    documents, jobs, store, extractor,
                    new HeadingAwareChunker(100, 0, 100),
                    embedding, retrieval,
                    new com.pulseink.config.properties.KnowledgeProperties(
                            null, 0, 0, 0, 0, 0, null, 0, 0, 0, null),
                    new SynchronousTransactions());

    @Test
    void successfulPipelineMarksDocumentActiveAndJobSucceeded() {
        seed(1L);
        coordinator.processForTest(1L);

        assertThat(documents.activeId).isEqualTo(1L);
        assertThat(documents.activeMime).isEqualTo("text/markdown");
        assertThat(documents.activeChunkCount).isEqualTo(2);
        assertThat(jobs.succeededId).isEqualTo(1L);
        assertThat(retrieval.replaced).isTrue();
        assertThat(retrieval.replacedChunks).isEqualTo(2);
        assertThat(embedding.calls).isEqualTo(1);
        assertThat(embedding.lastBatchSize).isEqualTo(2);
        assertThat(documents.activeIndex).isEqualTo(retrieval.physicalIndex);
    }

    @Test
    void embeddingFailureMarksBothFailedWithStableCode() {
        seed(1L);
        embedding.fail = new EmbeddingException("EMBEDDING_PROVIDER_FAILED", "sanitized");
        coordinator.processForTest(1L);

        assertThat(documents.failedCode).isEqualTo("EMBEDDING_PROVIDER_FAILED");
        assertThat(jobs.failedCode).isEqualTo("EMBEDDING_PROVIDER_FAILED");
        assertThat(retrieval.replaced).isFalse();
    }

    @Test
    void parseFailureMarksBothFailed() {
        seed(1L);
        extractor.fail = new IllegalArgumentException("document could not be parsed");
        coordinator.processForTest(1L);

        assertThat(documents.failedCode).isEqualTo("KNOWLEDGE_PARSE_FAILED");
        assertThat(jobs.failedCode).isEqualTo("KNOWLEDGE_PARSE_FAILED");
    }

    @Test
    void staleClaimDoesNotDoubleProcess() {
        seed(1L);
        jobs.failUpdate = true;
        coordinator.processForTest(1L);

        assertThat(documents.activeId).isZero();
        assertThat(jobs.succeededId).isZero();
        assertThat(retrieval.replaced).isFalse();
    }

    @Test
    void staleProcessingJobAndDocumentAreReclaimed() {
        seed(1L);
        documents.document.markProcessing();
        jobs.job.startProcessing(Instant.now().minusSeconds(3600));

        coordinator.processForTest(1L);

        assertThat(documents.activeId).isEqualTo(1L);
        assertThat(jobs.succeededId).isEqualTo(1L);
        assertThat(jobs.job.attempt()).isEqualTo(2);
    }

    private void seed(long documentId) {
        documents.document = KnowledgeDocument.materialize(
                documentId, "src-1", "guide.md", "key-1", "text/markdown", null, 5L,
                "a".repeat(64), KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1,
                KnowledgeDocumentStatus.PENDING, null, null, 0, null, 7L, 0L,
                Instant.now(), Instant.now());
        jobs.job = IngestionJob.materialize(
                documentId, "job-1", documentId, IngestionJobStatus.PENDING, 0, null,
                null, null, 0L, Instant.now(), Instant.now());
    }

    static final class FakeDocuments implements KnowledgeDocumentRepository {
        KnowledgeDocument document;
        long activeId;
        String activeMime;
        int activeChunkCount;
        String activeIndex;
        String failedCode;

        @Override
        public KnowledgeDocument insert(KnowledgeDocument document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<KnowledgeDocument> findById(long id) {
            return Optional.ofNullable(document);
        }

        @Override
        public Optional<KnowledgeDocument> findByChecksumAndType(String checksumSha256,
                                                                 KnowledgeType knowledgeType) {
            return Optional.empty();
        }

        @Override
        public void update(KnowledgeDocument document) {
            this.document = document;
        }

        @Override
        public void markProcessing(long id) {
            document.markProcessing();
        }

        @Override
        public void markActive(long id, String detectedMimeType, String embeddingProfileId,
                               String indexName, int chunkCount) {
            activeId = id;
            activeMime = detectedMimeType;
            activeChunkCount = chunkCount;
            activeIndex = indexName;
        }

        @Override
        public void markFailed(long id, String failureCode) {
            failedCode = failureCode;
        }

        @Override
        public void retry(long id) {
        }

        @Override
        public DocumentPage findPage(KnowledgeDocumentStatus status, KnowledgeType type,
                                     int page, int size) {
            return new DocumentPage(0, List.of());
        }

        @Override
        public List<KnowledgeDocument> findActiveByIds(List<Long> ids) {
            return List.of();
        }
    }

    static final class FakeJobs implements IngestionJobRepository {
        IngestionJob job;
        long succeededId;
        String failedCode;
        boolean failUpdate;

        @Override
        public IngestionJob insert(IngestionJob job) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<IngestionJob> findById(long id) {
            return Optional.ofNullable(job);
        }

        @Override
        public Optional<IngestionJob> findByDocumentId(long documentId) {
            return Optional.empty();
        }

        @Override
        public void update(IngestionJob job) {
            if (failUpdate) {
                throw new IllegalStateException("stale");
            }
            this.job = job;
        }

        @Override
        public void startProcessing(long id, Instant startedAt) {
        }

        @Override
        public void markSucceeded(long id, Instant completedAt) {
            succeededId = id;
        }

        @Override
        public void markFailed(long id, String failureCode, Instant completedAt) {
            failedCode = failureCode;
        }

        @Override
        public void retry(long id) {
        }

        @Override
        public List<IngestionJob> findRecoverable(int limit, java.time.Duration staleTimeout) {
            return List.of();
        }

        @Override
        public List<IngestionJob> findPending(int limit) {
            return List.of();
        }
    }

    static final class FakeStore implements OriginalDocumentStore {
        @Override
        public StoredDocument save(String originalFilename, String declaredMimeType,
                                   long maxBytes, InputStream content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream open(String storageKey) {
            return new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void delete(String storageKey) {
        }
    }

    static final class FakeExtractor implements DocumentTextExtractor {
        RuntimeException fail;

        @Override
        public ExtractedDocument extract(String originalFilename, InputStream content,
                                         long maxExtractedCharacters) {
            if (fail != null) {
                throw fail;
            }
            return new ExtractedDocument("t", "text/markdown", List.of(
                    new ExtractedSection("H1", "some text", 0),
                    new ExtractedSection("H2", "more text", 1)));
        }
    }

    static final class FakeEmbedding implements com.pulseink.service.embedding.EmbeddingPort {
        EmbeddingException fail;
        int calls;
        int lastBatchSize;

        @Override
        public com.pulseink.service.embedding.EmbeddingProfile profile() {
            return com.pulseink.service.embedding.EmbeddingProfile.of("fake", "m", 4);
        }

        @Override
        public com.pulseink.service.embedding.EmbeddingBatch embed(
                List<String> texts, com.pulseink.service.embedding.EmbeddingPurpose purpose) {
            if (fail != null) {
                throw fail;
            }
            calls++;
            lastBatchSize = texts.size();
            var vectors = new java.util.ArrayList<float[]>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new float[] {1f, 0f, 0f, 0f});
            }
            return new com.pulseink.service.embedding.EmbeddingBatch(
                    vectors);
        }
    }

    static final class FakeRetrieval implements RetrievalStore {
        boolean replaced;
        int replacedChunks;
        String physicalIndex = "pulseink-knowledge-v1-fake-profile";

        @Override
        public void ensureCompatibleIndex(com.pulseink.service.embedding.EmbeddingProfile profile) {
        }

        @Override
        public String physicalIndexName(
                com.pulseink.service.embedding.EmbeddingProfile profile) {
            return physicalIndex;
        }

        @Override
        public void replaceDocumentVersion(IndexedDocument document, List<EmbeddedChunk> chunks) {
            replaced = true;
            replacedChunks = chunks.size();
        }

        @Override
        public RetrievalCandidates search(HybridSearchQuery query) {
            return new RetrievalCandidates(RetrievalMode.HYBRID, null, List.of(), List.of());
        }

        @Override
        public void deleteDocumentVersion(long documentId, int documentVersion) {
        }
    }

    static final class SynchronousTransactions
            implements org.springframework.transaction.support.TransactionOperations {
        @Override
        public <T> T execute(
                org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(null);
        }
    }
}
