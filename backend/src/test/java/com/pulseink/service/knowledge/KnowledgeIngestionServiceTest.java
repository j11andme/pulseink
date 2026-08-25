package com.pulseink.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.IngestionJob;
import com.pulseink.domain.knowledge.IngestionJobStatus;
import com.pulseink.domain.knowledge.KnowledgeDocument;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeIngestionServiceTest {

    private final FakeStore store = new FakeStore();
    private final FakeDocuments documents = new FakeDocuments();
    private final FakeJobs jobs = new FakeJobs();
    private final FakeExtractor extractor = new FakeExtractor();
    private final FakeCoordinator coordinator = new FakeCoordinator();
    private final KnowledgeIngestionService service = new KnowledgeIngestionService(
            store, documents, jobs, extractor, coordinator,
            new KnowledgeSearchService(new NoopRetrievalStore(), documents, 60, 500),
            new com.pulseink.client.embedding.DeterministicFakeEmbeddingAdapter(),
            new org.springframework.transaction.support.TransactionOperations() {
                @Override
                public <T> T execute(
                        org.springframework.transaction.support.TransactionCallback<T> action) {
                    return action.doInTransaction(null);
                }
            });

    @Test
    void uploadSavesFileThenPersistsPendingDocumentAndJob() {
        var result = service.upload(command("guide.md", "hello"));

        assertThat(result.sourceId()).isNotBlank();
        assertThat(result.jobId()).isNotBlank();
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(store.saved).isTrue();
        assertThat(documents.inserted).isEqualTo(1);
        assertThat(jobs.inserted).isEqualTo(1);
        assertThat(coordinator.submitted).isEqualTo(1);
        assertThat(documents.lastStatus).isEqualTo(KnowledgeDocumentStatus.PENDING);
    }

    @Test
    void duplicateChecksumAndTypeIsRejectedAndDeletesNewFileOnce() {
        documents.existing = KnowledgeDocument.materialize(
                5L, "src-5", "guide.md", "key-5", "text/markdown", null, 5L, "sha-x",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1,
                KnowledgeDocumentStatus.PENDING, null, null, 0, null, 7L, 0L,
                Instant.now(), Instant.now());

        assertThatThrownBy(() -> service.upload(command("other.md", "hello")))
                .isInstanceOf(IngestKnowledgeUseCase.KnowledgeDocumentDuplicateException.class);
        assertThat(store.saved).isTrue();
        assertThat(store.deleteCount).isEqualTo(1);
        assertThat(documents.inserted).isZero();
        assertThat(jobs.inserted).isZero();
    }

    @Test
    void uploadFailsAndCompensatesWhenDatabaseInsertFails() {
        documents.failOnInsert = true;
        assertThatThrownBy(() -> service.upload(command("guide.md", "hello")))
                .isInstanceOf(RuntimeException.class);
        assertThat(store.deleteCount).isEqualTo(1);
    }

    private static com.pulseink.service.knowledge.IngestKnowledgeUseCase.UploadCommand command(
            String filename, String content) {
        return new com.pulseink.service.knowledge.IngestKnowledgeUseCase.UploadCommand(
                filename, "text/markdown", KnowledgeType.BRAND_GUIDELINE,
                EvidenceAuthority.OFFICIAL, 7L, 10_485_760L,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void retryIsAllowedOnlyForFailedDocuments() {
        documents.failedId = 9L;
        service.retry(9L);
        assertThat(documents.retried).isEqualTo(9L);
        assertThat(coordinator.submitted).isEqualTo(1);

        documents.failedId = -1L;
        assertThatThrownBy(() -> service.retry(9L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static final class FakeStore implements OriginalDocumentStore {
        boolean saved;
        int deleteCount;

        @Override
        public StoredDocument save(String originalFilename, String declaredMimeType,
                                   long maxBytes, InputStream content) {
            saved = true;
            return new StoredDocument("key-1", originalFilename, declaredMimeType, 5L,
                    "a".repeat(64));
        }

        @Override
        public InputStream open(String storageKey) {
            return new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void delete(String storageKey) {
            deleteCount++;
        }
    }

    static final class FakeDocuments implements KnowledgeDocumentRepository {
        int inserted;
        KnowledgeDocumentStatus lastStatus;
        KnowledgeDocument existing;
        boolean failOnInsert;
        long failedId = -1L;
        long retried = -1L;

        @Override
        public KnowledgeDocument insert(KnowledgeDocument document) {
            if (failOnInsert) {
                throw new RuntimeException("db down");
            }
            inserted++;
            lastStatus = document.status();
            return KnowledgeDocument.materialize(
                    (long) inserted, document.sourceId(), document.originalFilename(),
                    document.storageKey(), document.declaredMimeType(), null,
                    document.sizeBytes(), document.checksumSha256(),
                    document.knowledgeType(), document.authority(),
                    document.documentVersion(), document.status(), null, null, 0,
                    null, document.createdBy(), 0L, Instant.now(), Instant.now());
        }

        @Override
        public Optional<KnowledgeDocument> findById(long id) {
            if (id == failedId) {
                return Optional.of(KnowledgeDocument.materialize(
                        id, "src-" + id, "f.md", "key", "text/markdown", null, 1L,
                        "a".repeat(64), KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL,
                        1, KnowledgeDocumentStatus.FAILED, null, null, 0, "KNOWLEDGE_PARSE_FAILED",
                        7L, 0L, Instant.now(), Instant.now()));
            }
            return Optional.ofNullable(existing);
        }

        @Override
        public Optional<KnowledgeDocument> findByChecksumAndType(String checksumSha256,
                                                                 KnowledgeType knowledgeType) {
            return Optional.ofNullable(existing);
        }

        @Override
        public void update(KnowledgeDocument document) {
        }

        @Override
        public void markProcessing(long id) {
        }

        @Override
        public void markActive(long id, String detectedMimeType, String embeddingProfileId,
                               String indexName, int chunkCount) {
        }

        @Override
        public void markFailed(long id, String failureCode) {
        }

        @Override
        public void retry(long id) {
            retried = id;
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
        int inserted;
        long lastDocumentId;

        @Override
        public IngestionJob insert(IngestionJob job) {
            inserted++;
            lastDocumentId = job.documentId();
            return IngestionJob.materialize((long) inserted, job.jobId(),
                    job.documentId(), IngestionJobStatus.PENDING, 0, null, null, null,
                    0L, Instant.now(), Instant.now());
        }

        @Override
        public Optional<IngestionJob> findById(long id) {
            return Optional.empty();
        }

        @Override
        public Optional<IngestionJob> findByDocumentId(long documentId) {
            return Optional.of(IngestionJob.materialize(
                    1L, "job-1", documentId, IngestionJobStatus.FAILED, 1,
                    "KNOWLEDGE_PARSE_FAILED", Instant.now(), Instant.now(), 0L,
                    Instant.now(), Instant.now()));
        }

        @Override
        public void update(IngestionJob job) {
        }

        @Override
        public void startProcessing(long id, Instant startedAt) {
        }

        @Override
        public void markSucceeded(long id, Instant completedAt) {
        }

        @Override
        public void markFailed(long id, String failureCode, Instant completedAt) {
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

    static final class FakeExtractor implements DocumentTextExtractor {
        @Override
        public ExtractedDocument extract(String originalFilename, InputStream content,
                                         long maxExtractedCharacters) {
            return new ExtractedDocument("t", "text/markdown",
                    List.of(new ExtractedSection("H", "some text", 0)));
        }
    }

    static final class FakeCoordinator implements KnowledgeIngestionCoordinator {
        int submitted;

        @Override
        public void submit(long jobId) {
            submitted++;
        }

        @Override
        public void recover() {
        }

        @Override
        public void close() {
        }
    }

    static final class NoopRetrievalStore implements RetrievalStore {
        @Override
        public void ensureCompatibleIndex(com.pulseink.service.embedding.EmbeddingProfile profile) {
        }

        @Override
        public String physicalIndexName(
                com.pulseink.service.embedding.EmbeddingProfile profile) {
            return "test-index";
        }

        @Override
        public void replaceDocumentVersion(IndexedDocument document, List<EmbeddedChunk> chunks) {
        }

        @Override
        public RetrievalCandidates search(HybridSearchQuery query) {
            return new RetrievalCandidates(RetrievalMode.HYBRID, null, List.of(), List.of());
        }

        @Override
        public void deleteDocumentVersion(long documentId, int documentVersion) {
        }
    }
}
