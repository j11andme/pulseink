package com.pulseink.repository.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.IngestionJob;
import com.pulseink.domain.knowledge.IngestionJobStatus;
import com.pulseink.domain.knowledge.KnowledgeDocument;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.knowledge.IngestionJobRepository;
import com.pulseink.service.knowledge.KnowledgeDocumentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake"
})
class MybatisKnowledgeRepositoryIT {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("pulseink")
            .withUsername("pulseink")
            .withPassword("pulseink_dev");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private IngestionJobRepository jobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM ingestion_job");
        jdbcTemplate.execute("DELETE FROM knowledge_document");
    }

    @Test
    void documentRoundTripPreservesEveryField() {
        var document = KnowledgeDocument.create(
                "source-abc", "guide.md", "storage-key-1",
                "text/markdown", 2048L, "sha256value", KnowledgeType.BRAND_GUIDELINE,
                EvidenceAuthority.OFFICIAL, 7L);
        var persisted = documentRepository.insert(document);
        documentRepository.markProcessing(persisted.id());
        documentRepository.markActive(persisted.id(), "text/markdown",
                "fake:model:64", "pulseink-knowledge-v1-x", 12);

        var reloaded = documentRepository.findById(persisted.id()).orElseThrow();
        assertThat(reloaded.sourceId()).isEqualTo("source-abc");
        assertThat(reloaded.originalFilename()).isEqualTo("guide.md");
        assertThat(reloaded.storageKey()).isEqualTo("storage-key-1");
        assertThat(reloaded.declaredMimeType()).isEqualTo("text/markdown");
        assertThat(reloaded.detectedMimeType()).isEqualTo("text/markdown");
        assertThat(reloaded.sizeBytes()).isEqualTo(2048L);
        assertThat(reloaded.checksumSha256()).isEqualTo("sha256value");
        assertThat(reloaded.knowledgeType()).isEqualTo(KnowledgeType.BRAND_GUIDELINE);
        assertThat(reloaded.authority()).isEqualTo(EvidenceAuthority.OFFICIAL);
        assertThat(reloaded.documentVersion()).isEqualTo(1);
        assertThat(reloaded.status()).isEqualTo(KnowledgeDocumentStatus.ACTIVE);
        assertThat(reloaded.embeddingProfileId()).isEqualTo("fake:model:64");
        assertThat(reloaded.indexName()).isEqualTo("pulseink-knowledge-v1-x");
        assertThat(reloaded.chunkCount()).isEqualTo(12);
        assertThat(reloaded.createdBy()).isEqualTo(7L);
    }

    @Test
    void uniqueChecksumAndTypeRejectsDuplicateDocument() {
        var first = documentRepository.insert(KnowledgeDocument.create(
                "source-1", "a.md", "key-1", "text/markdown", 1L, "same-sha",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L));
        assertThat(first.id()).isPositive();

        assertThatThrownBy(() -> documentRepository.insert(KnowledgeDocument.create(
                "source-2", "b.md", "key-2", "text/markdown", 1L, "same-sha",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L)))
                .isInstanceOf(DuplicateKeyException.class);

        var differentType = documentRepository.insert(KnowledgeDocument.create(
                "source-3", "c.md", "key-3", "text/markdown", 1L, "same-sha",
                KnowledgeType.CHANNEL_RULE, EvidenceAuthority.OFFICIAL, 1L));
        assertThat(differentType.id()).isPositive();
    }

    @Test
    void casUpdateFailsOnStaleVersion() {
        var persisted = documentRepository.insert(KnowledgeDocument.create(
                "source-1", "a.md", "key-1", "text/markdown", 1L, "sha",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L));
        documentRepository.markProcessing(persisted.id());

        var stale = KnowledgeDocument.materialize(
                persisted.id(), "source-1", "a.md", "key-1", "text/markdown",
                null, 1L, "sha", KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL,
                1, KnowledgeDocumentStatus.PENDING, null, null, 0, null,
                persisted.createdBy(), 0L, persisted.createdAt(), persisted.updatedAt());
        stale.markProcessing();
        assertThatThrownBy(() -> documentRepository.update(stale))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void failedDocumentAndJobAreRetryable() {
        var persisted = documentRepository.insert(KnowledgeDocument.create(
                "source-1", "a.md", "key-1", "text/markdown", 1L, "sha",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L));
        documentRepository.markProcessing(persisted.id());
        documentRepository.markFailed(persisted.id(), "KNOWLEDGE_PARSE_FAILED");

        documentRepository.retry(persisted.id());
        var retried = documentRepository.findById(persisted.id()).orElseThrow();
        assertThat(retried.status()).isEqualTo(KnowledgeDocumentStatus.PENDING);
        assertThat(retried.failureCode()).isNull();
    }

    @Test
    void jobFieldsAndAttemptSurviveRoundTrip() {
        var document = documentRepository.insert(KnowledgeDocument.create(
                "source-1", "a.md", "key-1", "text/markdown", 1L, "sha",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L));
        var job = jobRepository.insert(IngestionJob.create("job-uuid-1", document.id()));
        jobRepository.startProcessing(job.id(), Instant.now());
        jobRepository.markFailed(job.id(), "EMBEDDING_PROVIDER_FAILED", Instant.now());

        var reloaded = jobRepository.findById(job.id()).orElseThrow();
        assertThat(reloaded.jobId()).isEqualTo("job-uuid-1");
        assertThat(reloaded.documentId()).isEqualTo(document.id());
        assertThat(reloaded.status()).isEqualTo(IngestionJobStatus.FAILED);
        assertThat(reloaded.attempt()).isEqualTo(1);
        assertThat(reloaded.failureCode()).isEqualTo("EMBEDDING_PROVIDER_FAILED");

        jobRepository.retry(job.id());
        var retried = jobRepository.findById(job.id()).orElseThrow();
        assertThat(retried.status()).isEqualTo(IngestionJobStatus.PENDING);
        assertThat(retried.failureCode()).isNull();
    }

    @Test
    void recoverableJobFilterSelectsOnlyEligibleJobs() {
        var doc1 = documentRepository.insert(KnowledgeDocument.create(
                "source-1", "a.md", "key-1", "text/markdown", 1L, "sha-1",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L));
        var doc2 = documentRepository.insert(KnowledgeDocument.create(
                "source-2", "b.md", "key-2", "text/markdown", 1L, "sha-2",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L));
        var doc3 = documentRepository.insert(KnowledgeDocument.create(
                "source-3", "c.md", "key-3", "text/markdown", 1L, "sha-3",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L));
        var doc4 = documentRepository.insert(KnowledgeDocument.create(
                "source-4", "d.md", "key-4", "text/markdown", 1L, "sha-4",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L));

        var pending = jobRepository.insert(IngestionJob.create("job-1", doc1.id()));
        var stuck = jobRepository.insert(IngestionJob.create("job-2", doc2.id()));
        jobRepository.startProcessing(stuck.id(), Instant.now().minus(Duration.ofMinutes(20)));
        var running = jobRepository.insert(IngestionJob.create("job-3", doc3.id()));
        jobRepository.startProcessing(running.id(), Instant.now());
        var succeeded = jobRepository.insert(IngestionJob.create("job-4", doc4.id()));
        jobRepository.startProcessing(succeeded.id(), Instant.now());
        jobRepository.markSucceeded(succeeded.id(), Instant.now());

        var recoverable = jobRepository.findRecoverable(100, Duration.ofMinutes(10));
        assertThat(recoverable).extracting(IngestionJob::jobId)
                .containsExactlyInAnyOrder("job-1", "job-2");
        assertThat(recoverable).extracting(IngestionJob::id)
                .contains(pending.id(), stuck.id());
    }

    @Test
    void documentPagingIsStableByCreatedDescIdDesc() {
        for (int i = 1; i <= 3; i++) {
            documentRepository.insert(KnowledgeDocument.create(
                    "source-" + i, "f" + i + ".md", "key-" + i, "text/markdown", 1L,
                    "sha-" + i, KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L));
        }
        var page = documentRepository.findPage(KnowledgeDocumentStatus.PENDING,
                KnowledgeType.PRODUCT, 0, 10);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).hasSize(3);
        assertThat(page.items().get(0).id()).isGreaterThan(page.items().get(2).id());
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
