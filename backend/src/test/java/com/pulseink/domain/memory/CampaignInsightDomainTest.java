package com.pulseink.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.campaign.CampaignChannel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CampaignInsightDomainTest {

    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void pendingCandidateCarriesPendingDefaults() {
        var insight = CampaignInsight.pending(
                1L, 2L, InsightCategory.CHANNEL_PATTERN, "标题", "正文结论",
                InsightScopeType.CHANNEL, "SOCIAL", List.of(CampaignChannel.SOCIAL),
                List.of(new InsightEvidenceRef(11L, 21L,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7))),
                0.78, List.of("样本窗口较短"), HASH, "insight-v1", 3L, NOW);

        assertThat(insight.id()).isZero();
        assertThat(insight.version()).isZero();
        assertThat(insight.status()).isEqualTo(InsightStatus.PENDING);
        assertThat(insight.indexStatus()).isEqualTo(InsightIndexStatus.NOT_INDEXED);
        assertThat(insight.reviewedBy()).isNull();
        assertThat(insight.reviewedAt()).isNull();
        assertThat(insight.indexedAt()).isNull();
    }

    @Test
    void rejectsConfidenceOutsideUnitRange() {
        assertThatThrownBy(() -> pending(b -> b.confidence = 1.01))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
        assertThatThrownBy(() -> pending(b -> b.confidence = -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void rejectsBlankTitleAndInsightText() {
        assertThatThrownBy(() -> pending(b -> b.title = "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> pending(b -> b.insightText = ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insightText");
    }

    @Test
    void channelScopeRequiresValueAndWorkspaceScopeForbidsIt() {
        assertThatThrownBy(() -> pending(b -> {
            b.scopeType = InsightScopeType.CHANNEL;
            b.scopeValue = " ";
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scopeValue");

        assertThatThrownBy(() -> pending(b -> {
            b.scopeType = InsightScopeType.WORKSPACE;
            b.scopeValue = "SOCIAL";
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WORKSPACE");

        var workspace = pending(b -> {
            b.scopeType = InsightScopeType.WORKSPACE;
            b.scopeValue = null;
        });
        assertThat(workspace.scopeValue()).isEmpty();
    }

    @Test
    void rejectsInvalidEvidenceRefs() {
        assertThatThrownBy(() -> pending(b -> b.evidence = List.of(
                new InsightEvidenceRef(0L, 21L,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentVersionId");
        assertThatThrownBy(() -> pending(b -> b.evidence = List.of(
                new InsightEvidenceRef(11L, 21L,
                        LocalDate.of(2026, 8, 7), LocalDate.of(2026, 8, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metric window");
    }

    @Test
    void rejectsEmptyEvidenceAndChannels() {
        assertThatThrownBy(() -> pending(b -> b.evidence = List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
        assertThatThrownBy(() -> pending(b -> b.channels = List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("applicableChannels");
    }

    @Test
    void rejectsTooManyChannels() {
        assertThatThrownBy(() -> pending(b -> b.channels = List.of(
                CampaignChannel.BLOG, CampaignChannel.SOCIAL,
                CampaignChannel.SHORT_VIDEO, CampaignChannel.BLOG)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("applicableChannels");
    }

    @Test
    void enforcesExplicitLengthCaps() {
        assertThatThrownBy(() -> pending(b -> b.title = "标".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> pending(b -> b.insightText = "文".repeat(2_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insightText");
        assertThatThrownBy(() -> pending(b -> b.limitations = List.of("限".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limitation");
        assertThatThrownBy(() -> reviewed(1L, "评".repeat(1_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewComment");
    }

    @Test
    void rejectsTooManyLimitations() {
        var limitations = java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> "limitation-" + index).toList();
        assertThatThrownBy(() -> pending(b -> b.limitations = limitations))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limitations");
    }

    @Test
    void rejectsMalformedSnapshotHash() {
        assertThatThrownBy(() -> pending(b -> b.snapshotHash = "not-a-sha256"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceSnapshotHash");
    }

    private static CampaignInsight pending(java.util.function.Consumer<Builder> mutate) {
        var builder = new Builder();
        mutate.accept(builder);
        return CampaignInsight.pending(
                builder.campaignId, builder.runId, builder.category, builder.title,
                builder.insightText, builder.scopeType, builder.scopeValue,
                builder.channels, builder.evidence, builder.confidence,
                builder.limitations, builder.snapshotHash, builder.promptVersion,
                builder.createdBy, builder.createdAt);
    }

    private static CampaignInsight reviewed(long reviewedBy, String comment) {
        var base = pending(builder -> {});
        return new CampaignInsight(
                base.id(), base.campaignId(), base.runId(), base.category(), base.title(),
                base.insightText(), base.scopeType(), base.scopeValue(),
                base.applicableChannels(), base.evidenceRefs(), base.confidence(),
                base.limitations(), base.sourceSnapshotHash(), base.promptVersion(),
                InsightStatus.APPROVED, InsightIndexStatus.INDEX_PENDING, 0, null, null,
                base.createdBy(), reviewedBy, comment, base.version(), base.createdAt(),
                NOW.plusSeconds(1), null);
    }

    private static final class Builder {
        long campaignId = 1L;
        long runId = 2L;
        InsightCategory category = InsightCategory.CHANNEL_PATTERN;
        String title = "标题";
        String insightText = "正文结论";
        InsightScopeType scopeType = InsightScopeType.CHANNEL;
        String scopeValue = "SOCIAL";
        List<CampaignChannel> channels = List.of(CampaignChannel.SOCIAL);
        List<InsightEvidenceRef> evidence = List.of(new InsightEvidenceRef(
                11L, 21L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7)));
        double confidence = 0.78;
        List<String> limitations = List.of("样本窗口较短");
        String snapshotHash = HASH;
        String promptVersion = "insight-v1";
        long createdBy = 3L;
        Instant createdAt = NOW;
    }
}
