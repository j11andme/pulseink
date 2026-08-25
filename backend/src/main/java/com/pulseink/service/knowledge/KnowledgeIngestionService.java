package com.pulseink.service.knowledge;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.IngestionJob;
import com.pulseink.domain.knowledge.KnowledgeDocument;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.knowledge.KnowledgeSearchService.SearchResult;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Upload orchestration: stream to the local store, insert PENDING document+job in one short
 * transaction (compensating file deletion on failure), then submit the job after commit.
 * Retry is FAILED-only and re-submits the job.
 */
public class KnowledgeIngestionService implements IngestKnowledgeUseCase, QueryKnowledgeUseCase {

    private final OriginalDocumentStore store;
    private final KnowledgeDocumentRepository documents;
    private final IngestionJobRepository jobs;
    private final DocumentTextExtractor extractor;
    private final KnowledgeIngestionCoordinator coordinator;
    private final KnowledgeSearchService searchService;
    private final com.pulseink.service.embedding.EmbeddingPort embedding;
    private final TransactionOperations transactions;

    public KnowledgeIngestionService(OriginalDocumentStore store,
                                     KnowledgeDocumentRepository documents,
                                     IngestionJobRepository jobs,
                                     DocumentTextExtractor extractor,
                                     KnowledgeIngestionCoordinator coordinator,
                                     KnowledgeSearchService searchService,
                                     com.pulseink.service.embedding.EmbeddingPort embedding,
                                     TransactionOperations transactions) {
        this.store = Objects.requireNonNull(store);
        this.documents = Objects.requireNonNull(documents);
        this.jobs = Objects.requireNonNull(jobs);
        this.extractor = Objects.requireNonNull(extractor);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.searchService = Objects.requireNonNull(searchService);
        this.embedding = Objects.requireNonNull(embedding);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public UploadResult upload(UploadCommand command) {
        Objects.requireNonNull(command, "upload command must not be null");
        var stored = store.save(
                command.originalFilename(),
                command.declaredMimeType(),
                command.maxBytes(),
                command.content());
        Registration registration;
        try {
            registration = transactions.execute(status -> register(command, stored));
        } catch (RuntimeException ex) {
            compensateFile(stored.storageKey(), ex);
            throw ex;
        }
        if (registration == null) {
            var failure = new IllegalStateException("knowledge registration transaction returned no result");
            compensateFile(stored.storageKey(), failure);
            throw failure;
        }
        coordinator.submit(registration.job().id());
        return new UploadResult(
                registration.document().id(),
                registration.document().sourceId(),
                registration.job().jobId(),
                registration.document().status().name());
    }

    @Override
    public void retry(long documentId) {
        Long jobId = transactions.execute(status -> resetFailedJob(documentId));
        if (jobId == null) {
            throw new IllegalStateException("knowledge retry transaction returned no job id");
        }
        coordinator.submit(jobId);
    }

    @Override
    public DocumentPage list(KnowledgeDocumentStatus status, KnowledgeType type,
                             int page, int size) {
        var result = documents.findPage(status, type, page, size);
        var items = result.items().stream()
                .map(this::toItem)
                .toList();
        return new DocumentPage(result.total(), items);
    }

    @Override
    public SearchResult search(String query, List<KnowledgeType> types,
                               List<EvidenceAuthority> authorities,
                               Instant updatedAfter, int topK) {
        var profile = embedding.profile();
        var hybrid = new HybridSearchQuery(
                query,
                types == null ? List.of() : types,
                authorities == null ? List.of() : authorities,
                updatedAfter,
                topK,
                Math.max(topK, 20),
                profile);
        return searchService.search(hybrid);
    }

    private DocumentItem toItem(KnowledgeDocument document) {
        return new DocumentItem(
                document.id(),
                document.sourceId(),
                document.originalFilename(),
                document.declaredMimeType(),
                document.detectedMimeType(),
                document.sizeBytes(),
                document.knowledgeType(),
                document.authority(),
                document.documentVersion(),
                document.status(),
                document.chunkCount(),
                document.failureCode(),
                document.createdAt(),
                document.updatedAt());
    }

    private Registration register(UploadCommand command, StoredDocument stored) {
        if (documents.findByChecksumAndType(
                stored.checksumSha256(), command.knowledgeType()).isPresent()) {
            throw new IngestKnowledgeUseCase.KnowledgeDocumentDuplicateException();
        }
        var document = KnowledgeDocument.create(
                UUID.randomUUID().toString(),
                stored.originalFilename(),
                stored.storageKey(),
                stored.declaredMimeType(),
                stored.sizeBytes(),
                stored.checksumSha256(),
                command.knowledgeType(),
                command.authority(),
                command.createdBy());
        var persisted = documents.insert(document);
        var job = jobs.insert(IngestionJob.create(UUID.randomUUID().toString(), persisted.id()));
        return new Registration(persisted, job);
    }

    private long resetFailedJob(long documentId) {
        var document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "knowledge document " + documentId + " was not found"));
        if (document.status() != KnowledgeDocumentStatus.FAILED) {
            throw new IllegalStateException(
                    "knowledge document " + documentId + " is not retryable");
        }
        var job = jobs.findByDocumentId(documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "ingestion job for document " + documentId + " was not found"));
        if (job.status() != com.pulseink.domain.knowledge.IngestionJobStatus.FAILED) {
            throw new IllegalStateException(
                    "ingestion job for document " + documentId + " is not retryable");
        }
        documents.retry(documentId);
        jobs.retry(job.id());
        return job.id();
    }

    private void compensateFile(String storageKey, RuntimeException original) {
        try {
            store.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private record Registration(KnowledgeDocument document, IngestionJob job) {
    }
}
