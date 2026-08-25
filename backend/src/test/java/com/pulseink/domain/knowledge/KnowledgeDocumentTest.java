package com.pulseink.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeDocumentTest {

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    private KnowledgeDocument newDocument() {
        return KnowledgeDocument.create(
                "source-1", "guide.md", "storage-key-1",
                "text/markdown", 1024L, "abc123", KnowledgeType.BRAND_GUIDELINE,
                EvidenceAuthority.OFFICIAL, 7L);
    }

    @Test
    void createProducesPendingDocumentWithDefaultVersion() {
        var document = newDocument();

        assertThat(document.sourceId()).isEqualTo("source-1");
        assertThat(document.originalFilename()).isEqualTo("guide.md");
        assertThat(document.storageKey()).isEqualTo("storage-key-1");
        assertThat(document.declaredMimeType()).isEqualTo("text/markdown");
        assertThat(document.detectedMimeType()).isNull();
        assertThat(document.sizeBytes()).isEqualTo(1024L);
        assertThat(document.checksumSha256()).isEqualTo("abc123");
        assertThat(document.knowledgeType()).isEqualTo(KnowledgeType.BRAND_GUIDELINE);
        assertThat(document.authority()).isEqualTo(EvidenceAuthority.OFFICIAL);
        assertThat(document.documentVersion()).isEqualTo(1);
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PENDING);
        assertThat(document.chunkCount()).isZero();
        assertThat(document.createdBy()).isEqualTo(7L);
        assertThat(document.version()).isZero();
    }

    @Test
    void rejectsBlankAndInvalidFields() {
        assertThatThrownBy(() -> KnowledgeDocument.create(
                "", "guide.md", "key", "text/markdown", 1L, "abc",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeDocument.create(
                "s", "", "key", "text/markdown", 1L, "abc",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeDocument.create(
                "s", "f.md", "", "text/markdown", 1L, "abc",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeDocument.create(
                "s", "f.md", "key", "", 1L, "abc",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeDocument.create(
                "s", "f.md", "key", "text/markdown", 0L, "abc",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeDocument.create(
                "s", "f.md", "key", "text/markdown", 1L, "abc",
                null, EvidenceAuthority.OFFICIAL, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> KnowledgeDocument.create(
                "s", "f.md", "key", "text/markdown", 1L, "abc",
                KnowledgeType.PRODUCT, null, 1L))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> KnowledgeDocument.create(
                "s", "f.md", "key", "text/markdown", 1L, "abc",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusTransitionsAreEnforced() {
        var document = newDocument();

        document.markProcessing();
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PROCESSING);

        document.markActive("text/markdown", "fake:model:64", "pulseink-knowledge-v1-x",
                12);
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.ACTIVE);
        assertThat(document.detectedMimeType()).isEqualTo("text/markdown");
        assertThat(document.embeddingProfileId()).isEqualTo("fake:model:64");
        assertThat(document.indexName()).isEqualTo("pulseink-knowledge-v1-x");
        assertThat(document.chunkCount()).isEqualTo(12);
    }

    @Test
    void illegalTransitionsAreRejected() {
        var document = newDocument();
        assertThatThrownBy(() -> document.markActive("mime", "p", "idx", 1))
                .isInstanceOf(IllegalStateException.class);

        document.markProcessing();
        assertThatThrownBy(document::markProcessing)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(document::retry)
                .isInstanceOf(IllegalStateException.class);

        document.markFailed("KNOWLEDGE_PARSE_FAILED");
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.FAILED);
        assertThat(document.failureCode()).isEqualTo("KNOWLEDGE_PARSE_FAILED");
        assertThatThrownBy(() -> document.markFailed("X"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retryIsAllowedOnlyFromFailed() {
        var document = newDocument();
        document.markProcessing();
        document.markFailed("KNOWLEDGE_PARSE_FAILED");

        document.retry();

        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PENDING);
        assertThat(document.failureCode()).isNull();
    }

    @Test
    void activeDocumentCannotBeRetriedOrFailed() {
        var document = newDocument();
        document.markProcessing();
        document.markActive("text/markdown", "p:1", "idx", 1);
        assertThatThrownBy(document::retry)
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> document.markFailed("X"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void materializeRestoresFullState() {
        var document = KnowledgeDocument.materialize(
                9L, "source-9", "f.md", "key-9", "text/markdown",
                "text/plain", 100L, "sha", KnowledgeType.PRODUCT,
                EvidenceAuthority.REFERENCE, 3, KnowledgeDocumentStatus.ACTIVE,
                "fake:1", "index-1", 5, null, 2L, 4L, NOW, NOW);

        assertThat(document.id()).isEqualTo(9L);
        assertThat(document.documentVersion()).isEqualTo(3);
        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.ACTIVE);
        assertThat(document.embeddingProfileId()).isEqualTo("fake:1");
        assertThat(document.chunkCount()).isEqualTo(5);
        assertThat(document.version()).isEqualTo(4L);
        assertThat(document.createdAt()).isEqualTo(NOW);
        assertThat(document.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void documentIsImmutableSnapshot() {
        var document = newDocument();
        document.markProcessing();
        document.markFailed("X");
        document.retry();

        assertThat(document.status()).isEqualTo(KnowledgeDocumentStatus.PENDING);
        assertThat(document.version()).isZero();
        assertThat(document.chunkCount()).isZero();
    }
}
