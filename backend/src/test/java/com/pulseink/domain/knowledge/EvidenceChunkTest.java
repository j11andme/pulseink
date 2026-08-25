package com.pulseink.domain.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvidenceChunkTest {

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    private EvidenceChunk chunk() {
        return new EvidenceChunk(
                "source-1", 5L, 1, "chunk-1", "Guide", "Guide > Colors",
                "The brand color is blue.", 0.9, Set.of("LEXICAL", "VECTOR"),
                KnowledgeType.BRAND_GUIDELINE, EvidenceAuthority.OFFICIAL, NOW);
    }

    @Test
    void exposesAllEvidenceFieldsWithoutSensitiveData() {
        var chunk = chunk();
        assertThat(chunk.sourceId()).isEqualTo("source-1");
        assertThat(chunk.documentId()).isEqualTo(5L);
        assertThat(chunk.documentVersion()).isEqualTo(1);
        assertThat(chunk.chunkId()).isEqualTo("chunk-1");
        assertThat(chunk.title()).isEqualTo("Guide");
        assertThat(chunk.headingPath()).isEqualTo("Guide > Colors");
        assertThat(chunk.snippet()).isEqualTo("The brand color is blue.");
        assertThat(chunk.score()).isEqualTo(0.9);
        assertThat(chunk.channels()).containsExactlyInAnyOrder("LEXICAL", "VECTOR");
        assertThat(chunk.knowledgeType()).isEqualTo(KnowledgeType.BRAND_GUIDELINE);
        assertThat(chunk.authority()).isEqualTo(EvidenceAuthority.OFFICIAL);
        assertThat(chunk.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void isImmutableRecord() {
        var channels = new java.util.HashSet<>(List.of("LEXICAL"));
        var chunk = new EvidenceChunk(
                "source-1", 5L, 1, "chunk-1", "t", "p", "s", 0.5, channels,
                KnowledgeType.PRODUCT, EvidenceAuthority.VERIFIED, NOW);
        channels.add("VECTOR");
        assertThat(chunk.channels()).containsExactly("LEXICAL");
        assertThatThrownBy(() -> chunk.channels().add("VECTOR"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsInvalidFields() {
        assertThatThrownBy(() -> new EvidenceChunk(
                "", 5L, 1, "c", "t", "p", "s", 0.5, Set.of(),
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceChunk(
                "s", 0L, 1, "c", "t", "p", "s", 0.5, Set.of(),
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceChunk(
                "s", 5L, 0, "c", "t", "p", "s", 0.5, Set.of(),
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceChunk(
                "s", 5L, 1, "", "t", "p", "s", 0.5, Set.of(),
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceChunk(
                "s", 5L, 1, "c", "t", "p", "", 0.5, Set.of(),
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceChunk(
                "s", 5L, 1, "c", "t", "p", "s", -0.1, Set.of(),
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enumsHaveFixedValues() {
        assertThat(java.util.EnumSet.allOf(KnowledgeType.class)).containsExactlyInAnyOrder(
                KnowledgeType.PRODUCT, KnowledgeType.BRAND_GUIDELINE,
                KnowledgeType.CHANNEL_RULE, KnowledgeType.APPROVED_EXAMPLE);
        assertThat(java.util.EnumSet.allOf(EvidenceAuthority.class)).containsExactlyInAnyOrder(
                EvidenceAuthority.OFFICIAL, EvidenceAuthority.VERIFIED,
                EvidenceAuthority.REFERENCE);
        assertThat(java.util.EnumSet.allOf(KnowledgeDocumentStatus.class))
                .containsExactlyInAnyOrder(
                        KnowledgeDocumentStatus.PENDING, KnowledgeDocumentStatus.PROCESSING,
                        KnowledgeDocumentStatus.ACTIVE, KnowledgeDocumentStatus.FAILED);
        assertThat(java.util.EnumSet.allOf(IngestionJobStatus.class)).containsExactlyInAnyOrder(
                IngestionJobStatus.PENDING, IngestionJobStatus.PROCESSING,
                IngestionJobStatus.SUCCEEDED, IngestionJobStatus.FAILED);
    }
}
