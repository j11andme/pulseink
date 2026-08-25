package com.pulseink.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.KnowledgeType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RrfFusionTest {

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    private final RrfFusion fusion = new RrfFusion();

    private RetrievalCandidates.Candidate candidate(String chunkId, long documentId) {
        return new RetrievalCandidates.Candidate(
                chunkId, documentId, 1, 1.0, "t", "p", "text", chunkId + "-src",
                KnowledgeType.PRODUCT, EvidenceAuthority.OFFICIAL, NOW);
    }

    @Test
    void fusesBothBranchesWithRankFromOne() {
        var lexical = List.of(candidate("c1", 1L), candidate("c2", 2L));
        var vector = List.of(candidate("c2", 2L), candidate("c3", 3L));

        var fused = fusion.fuse(lexical, vector, 10, 60);

        double c1 = 1.0 / (60 + 1);
        double c2 = 1.0 / (60 + 2) + 1.0 / (60 + 1);
        double c3 = 1.0 / (60 + 2);
        assertThat(fused).extracting(RrfFusion.FusedResult::rrfScore)
                .containsExactly(c2, c1, c3);
        assertThat(fused).extracting(RrfFusion.FusedResult::chunkId)
                .containsExactly("c2", "c1", "c3");
        assertThat(fused.get(0).channels()).containsExactlyInAnyOrder("LEXICAL", "VECTOR");
        assertThat(fused.get(1).channels()).containsExactly("LEXICAL");
    }

    @Test
    void duplicateChunkWithinBranchCountsOnce() {
        var lexical = List.of(candidate("c1", 1L), candidate("c1", 1L));
        var fused = fusion.fuse(lexical, List.of(), 10, 60);
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).rrfScore()).isCloseTo(
                1.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void singleBranchFusionWorks() {
        var lexical = List.of(candidate("c1", 1L), candidate("c2", 2L));
        var fused = fusion.fuse(lexical, List.of(), 10, 60);
        assertThat(fused).extracting(RrfFusion.FusedResult::chunkId)
                .containsExactly("c1", "c2");
        assertThat(fused.get(0).channels()).containsExactly("LEXICAL");
    }

    @Test
    void stableTieBreakByChunkId() {
        var fused = fusion.fuse(
                List.of(candidate("b", 1L)),
                List.of(candidate("a", 2L)),
                10, 60);
        assertThat(fused).extracting(RrfFusion.FusedResult::chunkId)
                .containsExactly("a", "b");
        assertThat(fused.get(0).rrfScore()).isCloseTo(
                fused.get(1).rrfScore(), org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void topKIsRespected() {
        var lexical = List.of(candidate("c1", 1L), candidate("c2", 2L), candidate("c3", 3L));
        var fused = fusion.fuse(lexical, List.of(), 2, 60);
        assertThat(fused).hasSize(2);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> fusion.fuse(null, List.of(), 10, 60))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> fusion.fuse(List.of(), List.of(), 0, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fusion.fuse(List.of(), List.of(), 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
