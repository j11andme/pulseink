package com.pulseink.service.knowledge;

import com.pulseink.config.properties.KnowledgeProperties;
import com.pulseink.domain.knowledge.IngestionJob;
import com.pulseink.domain.knowledge.KnowledgeDocument;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.embedding.EmbeddingPort.EmbeddingException;
import com.pulseink.service.embedding.EmbeddingProfile;
import com.pulseink.service.embedding.EmbeddingPurpose;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Ingestion job scheduling boundary. Submit hands a job to the async worker; recover re-submits
 * PENDING and stale PROCESSING jobs after startup.
 */
public interface KnowledgeIngestionCoordinator extends AutoCloseable {

    void submit(long jobId);

    void recover();

    /**
     * Virtual-thread worker. Jobs are claimed with CAS in a short transaction, then the pipeline
     * (open → Tika → chunk → embed → ensure index → replace version) runs outside any database
     * transaction. Success marks the document ACTIVE and the job SUCCEEDED; failures write
     * stable codes.
     */
    final class Default implements KnowledgeIngestionCoordinator {

        private static final int RECOVERY_LIMIT = 100;

        private final KnowledgeDocumentRepository documents;
        private final IngestionJobRepository jobs;
        private final OriginalDocumentStore store;
        private final DocumentTextExtractor extractor;
        private final HeadingAwareChunker chunker;
        private final EmbeddingPort embedding;
        private final RetrievalStore retrievalStore;
        private final KnowledgeProperties properties;
        private final TransactionOperations transactions;
        private final ExecutorService executor;

        public Default(KnowledgeDocumentRepository documents,
                       IngestionJobRepository jobs,
                       OriginalDocumentStore store,
                       DocumentTextExtractor extractor,
                       HeadingAwareChunker chunker,
                       EmbeddingPort embedding,
                       RetrievalStore retrievalStore,
                       KnowledgeProperties properties,
                       TransactionOperations transactions) {
            this.documents = Objects.requireNonNull(documents);
            this.jobs = Objects.requireNonNull(jobs);
            this.store = Objects.requireNonNull(store);
            this.extractor = Objects.requireNonNull(extractor);
            this.chunker = Objects.requireNonNull(chunker);
            this.embedding = Objects.requireNonNull(embedding);
            this.retrievalStore = Objects.requireNonNull(retrievalStore);
            this.properties = Objects.requireNonNull(properties);
            this.transactions = Objects.requireNonNull(transactions);
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
        }

        @Override
        public void submit(long jobId) {
            executor.execute(() -> process(jobId));
        }

        void processForTest(long jobId) {
            process(jobId);
        }

        @Override
        public void recover() {
            for (var job : jobs.findRecoverable(RECOVERY_LIMIT, properties.staleJobTimeout())) {
                submit(job.id());
            }
        }

        @Override
        public void close() {
            executor.close();
        }

        private void process(long jobId) {
            try {
                var job = jobs.findById(jobId).orElse(null);
                if (job == null) {
                    return;
                }
                var document = documents.findById(job.documentId()).orElse(null);
                if (document == null) {
                    return;
                }
                if (!claim(job, document)) {
                    return;
                }
                try {
                    runPipeline(job, document);
                } catch (Exception ex) {
                    fail(job, document, stableCode(ex));
                }
            } catch (Exception ignored) {
                // secondary failures never rethrow into the executor
            }
        }

        private boolean claim(IngestionJob job, KnowledgeDocument document) {
            try {
                Boolean claimed = transactions.execute(status -> {
                    var now = Instant.now();
                    if (!job.isRecoverable(now, properties.staleJobTimeout())) {
                        return false;
                    }
                    if (document.status()
                            != com.pulseink.domain.knowledge.KnowledgeDocumentStatus.PENDING
                            && document.status()
                            != com.pulseink.domain.knowledge.KnowledgeDocumentStatus.PROCESSING) {
                        return false;
                    }
                    if (job.status()
                            == com.pulseink.domain.knowledge.IngestionJobStatus.PENDING) {
                        job.startProcessing(now);
                    } else {
                        job.reclaimProcessing(now);
                    }
                    jobs.update(job);
                    if (document.status()
                            == com.pulseink.domain.knowledge.KnowledgeDocumentStatus.PENDING) {
                        document.markProcessing();
                        documents.update(document);
                    }
                    return true;
                });
                return Boolean.TRUE.equals(claimed);
            } catch (IllegalStateException stale) {
                return false;
            }
        }

        private void runPipeline(IngestionJob job, KnowledgeDocument document) {
            var profile = embedding.profile();
            ExtractedDocument extracted;
            try (var content = store.open(document.storageKey())) {
                extracted = extractor.extract(
                        document.originalFilename(),
                        content,
                        properties.maxExtractedCharacters());
            } catch (java.io.IOException ex) {
                throw new IllegalArgumentException("knowledge file could not be read", ex);
            }
            var chunks = chunker.chunk(extracted);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("document contains no chunkable text");
            }
            var batch = embedding.embed(
                    chunks.stream().map(KnowledgeChunk::text).toList(),
                    EmbeddingPurpose.INDEX);
            var embedded = new ArrayList<EmbeddedChunk>(chunks.size());
            var indexedAt = Instant.now();
            int ordinal = 0;
            for (var chunk : chunks) {
                embedded.add(new EmbeddedChunk(
                        document.id() + ":" + document.documentVersion() + ":" + ordinal,
                        document.id(),
                        document.documentVersion(),
                        document.sourceId(),
                        ordinal,
                        extracted.title(),
                        chunk.headingPath(),
                        chunk.text(),
                        document.knowledgeType(),
                        document.authority(),
                        "ACTIVE",
                        profile.profileId(),
                        indexedAt,
                        batch.vectors().get(ordinal)));
                ordinal++;
            }
            retrievalStore.ensureCompatibleIndex(profile);
            String physicalIndex = retrievalStore.physicalIndexName(profile);
            retrievalStore.replaceDocumentVersion(new IndexedDocument(
                    document.id(),
                    document.documentVersion(),
                    document.sourceId(),
                    extracted.title(),
                    document.knowledgeType(),
                    document.authority(),
                    indexedAt), List.copyOf(embedded));

            transactions.executeWithoutResult(status -> {
                documents.markActive(
                        document.id(),
                        extracted.detectedMimeType(),
                        profile.profileId(),
                        physicalIndex,
                        embedded.size());
                jobs.markSucceeded(job.id(), Instant.now());
            });
        }

        private void fail(IngestionJob job, KnowledgeDocument document, String code) {
            transactions.executeWithoutResult(status -> {
                try {
                    documents.markFailed(document.id(), code);
                } catch (IllegalStateException ignored) {
                    // document may already be ACTIVE; the job failure is authoritative
                }
                try {
                    jobs.markFailed(job.id(), code, Instant.now());
                } catch (IllegalStateException ignored) {
                    // job may have been claimed by another worker
                }
            });
        }

        private static String stableCode(Exception ex) {
            if (ex instanceof EmbeddingException embeddingException) {
                return embeddingException.code();
            }
            if (ex instanceof IllegalStateException) {
                return "KNOWLEDGE_INDEX_UNAVAILABLE";
            }
            return "KNOWLEDGE_PARSE_FAILED";
        }
    }
}
