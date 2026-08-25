package com.pulseink.client.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.pulseink.client.embedding.DeterministicFakeEmbeddingAdapter;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.CampaignInsight;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightEvidenceRef;
import com.pulseink.domain.memory.InsightScopeType;
import com.pulseink.domain.memory.InsightStatus;
import com.pulseink.domain.memory.InsightIndexStatus;
import com.pulseink.service.embedding.EmbeddingPort;
import com.pulseink.service.embedding.EmbeddingPurpose;
import com.pulseink.service.memory.ApprovedInsightHit;
import com.pulseink.support.MemoryElasticsearchTestContainer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovedInsightSearchIT {

    private final EmbeddingPort embedding = new DeterministicFakeEmbeddingAdapter();
    private final String alias = "pulseink-memory-it-" + UUID.randomUUID().toString().substring(0, 8);
    private ElasticsearchInsightStore store;

    @BeforeEach
    void setUp() {
        var client = ElasticsearchClient.of(c -> c
                .host(java.net.URI.create("http://" + MemoryElasticsearchTestContainer.httpHostAddress()))
                .jsonMapper(new JacksonJsonpMapper()));
        store = new ElasticsearchInsightStore(client, alias, new ElasticsearchInsightStore.EmbeddingAdapter() {
            @Override
            public com.pulseink.service.embedding.EmbeddingProfile profile() {
                return embedding.profile();
            }

            @Override
            public float[] embed(String text) {
                return embedding.embed(List.of(text), EmbeddingPurpose.QUERY).vectors().get(0);
            }
        });
        store.ensureCompatibleIndex(embedding.profile());
    }

    @Test
    void approvedInsightBecomesSearchableWithOriginAndChannelFilter() {
        var blog = approved(31L, 10L, "博客使用短段落提升完读",
                InsightScopeType.CHANNEL, "BLOG", List.of(CampaignChannel.BLOG));
        var social = approved(32L, 10L, "社交短句互动更高",
                InsightScopeType.CHANNEL, "SOCIAL", List.of(CampaignChannel.SOCIAL));
        store.indexApproved(blog);
        store.indexApproved(social);

        var hits = store.search("短句 互动", null, 3);
        assertThat(hits).extracting(ApprovedInsightHit::title)
                .contains("社交短句互动更高");
        assertThat(hits.getFirst().sourceCampaignId()).isEqualTo(10L);
        assertThat(hits.getFirst().approvedAt()).isNotNull();

        var socialOnly = store.search("短句 互动", CampaignChannel.SOCIAL, 3);
        assertThat(socialOnly).extracting(ApprovedInsightHit::title)
                .containsExactly("社交短句互动更高");

        var blogOnly = store.search("短句 互动", CampaignChannel.BLOG, 3);
        assertThat(blogOnly).extracting(ApprovedInsightHit::title)
                .containsExactly("博客使用短段落提升完读");
    }

    @Test
    void searchResultsAreDeduplicatedAndCappedByTopK() {
        var first = approved(33L, 10L, "统一品牌语气保持一致",
                InsightScopeType.WORKSPACE, "", List.of(CampaignChannel.BLOG, CampaignChannel.SOCIAL));
        var second = approved(34L, 10L, "语气一致的品牌内容",
                InsightScopeType.WORKSPACE, "", List.of(CampaignChannel.BLOG));
        store.indexApproved(first);
        store.indexApproved(second);

        var hits = store.search("品牌语气", null, 10);
        assertThat(hits).hasSizeLessThanOrEqualTo(2);
        assertThat(hits.stream().map(hit -> hit.insightId()).distinct().count())
                .isEqualTo(hits.size());
    }

    @Test
    void pendingAndRejectedInsightsAreRefusedByTheStore() {
        var pending = pendingInsight(35L);
        var rejected = rejectedInsight(36L);

        assertThatThrownBy(() -> store.indexApproved(pending))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPROVED");
        assertThatThrownBy(() -> store.indexApproved(rejected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("APPROVED");

        assertThat(store.search("任何查询", null, 3)).isEmpty();
    }

    @Test
    void existingAliasWithAnotherEmbeddingProfileIsRejected() {
        var incompatibleProfile = com.pulseink.service.embedding.EmbeddingProfile.of(
                "fake", "another-model", embedding.profile().dimensions());
        var incompatible = new ElasticsearchInsightStore(
                ElasticsearchClient.of(c -> c
                        .host(java.net.URI.create(
                                "http://" + MemoryElasticsearchTestContainer.httpHostAddress()))
                        .jsonMapper(new JacksonJsonpMapper())),
                alias,
                new ElasticsearchInsightStore.EmbeddingAdapter() {
                    @Override
                    public com.pulseink.service.embedding.EmbeddingProfile profile() {
                        return incompatibleProfile;
                    }

                    @Override
                    public float[] embed(String text) {
                        return new float[incompatibleProfile.dimensions()];
                    }
                });

        assertThatThrownBy(() -> incompatible.ensureCompatibleIndex(incompatibleProfile))
                .isInstanceOf(com.pulseink.service.memory.InsightException.class)
                .satisfies(error -> assertThat(
                        ((com.pulseink.service.memory.InsightException) error).code())
                        .isEqualTo(com.pulseink.service.memory.InsightErrorCode
                                .INSIGHT_SEARCH_UNAVAILABLE));
    }

    private static CampaignInsight approved(long id, long campaignId, String text,
                                            InsightScopeType scope, String scopeValue,
                                            List<CampaignChannel> channels) {
        var pending = CampaignInsight.pending(
                campaignId, id, InsightCategory.CHANNEL_PATTERN, text, text,
                scope, scopeValue, channels,
                List.of(new InsightEvidenceRef(11L, 21L,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7))),
                0.8, List.of("窗口短"), "a".repeat(64), "insight-v1", 1L,
                Instant.parse("2026-08-14T08:00:00Z"));
        return new CampaignInsight(id, pending.campaignId(), pending.runId(),
                pending.category(), pending.title(), pending.insightText(),
                pending.scopeType(), pending.scopeValue(), pending.applicableChannels(),
                pending.evidenceRefs(), pending.confidence(), pending.limitations(),
                pending.sourceSnapshotHash(), pending.promptVersion(),
                InsightStatus.APPROVED, InsightIndexStatus.INDEX_PENDING, 0, null, null,
                pending.createdBy(), 1L, "ok", 1L, pending.createdAt(),
                Instant.parse("2026-08-14T09:00:00Z"), null);
    }

    private static CampaignInsight pendingInsight(long id) {
        return CampaignInsight.pending(
                10L, id, InsightCategory.REUSABLE_CASE, "候选", "候选正文",
                InsightScopeType.WORKSPACE, "", List.of(CampaignChannel.BLOG),
                List.of(new InsightEvidenceRef(11L, 21L,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7))),
                0.5, List.of(), "b".repeat(64), "insight-v1", 1L,
                Instant.parse("2026-08-14T08:00:00Z"));
    }

    private static CampaignInsight rejectedInsight(long id) {
        var pending = pendingInsight(id);
        return new CampaignInsight(id, pending.campaignId(), pending.runId(),
                pending.category(), pending.title(), pending.insightText(),
                pending.scopeType(), pending.scopeValue(), pending.applicableChannels(),
                pending.evidenceRefs(), pending.confidence(), pending.limitations(),
                pending.sourceSnapshotHash(), pending.promptVersion(),
                InsightStatus.REJECTED, InsightIndexStatus.NOT_INDEXED, 0, null, null,
                pending.createdBy(), 1L, "no", 1L, pending.createdAt(),
                Instant.parse("2026-08-14T09:00:00Z"), null);
    }
}
