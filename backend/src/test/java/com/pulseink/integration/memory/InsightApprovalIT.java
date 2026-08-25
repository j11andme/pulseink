package com.pulseink.integration.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.domain.memory.CampaignInsight;
import com.pulseink.domain.memory.InsightIndexStatus;
import com.pulseink.domain.memory.InsightStatus;
import com.pulseink.service.memory.CampaignInsightRepository;
import com.pulseink.service.memory.ConsolidateInsightUseCase;
import com.pulseink.service.memory.InsightDecision;
import com.pulseink.service.memory.InsightErrorCode;
import com.pulseink.service.memory.InsightException;
import com.pulseink.service.memory.InsightIndexWorker;
import com.pulseink.service.memory.InsightSearchStore;
import com.pulseink.service.memory.QueryInsightUseCase;
import com.pulseink.service.memory.ReviewInsightUseCase;
import com.pulseink.support.MemoryTestContainers;
import com.pulseink.support.MemoryElasticsearchTestContainer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full approval pipeline over real MySQL + real Elasticsearch: user-triggered candidate,
 * human decision, worker indexing and derived searchability. The fake model port is scripted
 * with valid insight fixtures so no provider is ever contacted.
 */
@SpringBootTest(properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake",
        "pulseink.embedding.provider=fake",
        "pulseink.publication.worker-enabled=false",
        "pulseink.feedback.consumer-enabled=false",
        "pulseink.memory.index-worker-enabled=false",
        "pulseink.run-lease.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class InsightApprovalIT {

    private static final String VALID_INSIGHT_JSON = """
            {"schemaVersion":1,"category":"CHANNEL_PATTERN",
             "title":"社交短句互动更高","insightText":"短句形式能提升互动",
             "scopeType":"CHANNEL","scopeValue":"SOCIAL",
             "applicableChannels":["SOCIAL"],
             "evidenceRefs":[{"contentVersionId":11,"publicationId":21,
                              "metricFrom":"2026-08-13","metricTo":"2026-08-13"}],
             "confidence":0.78,"limitations":["样本窗口较短"]}
            """;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MemoryTestContainers::mysqlUrl);
        registry.add("spring.datasource.username", MemoryTestContainers::mysqlUsername);
        registry.add("spring.datasource.password", MemoryTestContainers::mysqlPassword);
        registry.add("spring.elasticsearch.uris",
                () -> "http://" + MemoryElasticsearchTestContainer.httpHostAddress());
        registry.add("pulseink.memory.index-alias",
                () -> "pulseink-memory-approval-it-" + UUID.randomUUID().toString()
                        .substring(0, 8));
    }

    @Autowired ConsolidateInsightUseCase consolidation;
    @Autowired ReviewInsightUseCase review;
    @Autowired QueryInsightUseCase query;
    @Autowired InsightIndexWorker worker;
    @Autowired CampaignInsightRepository insightRepository;
    @Autowired StoreProbe probe;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[] {"campaign_insight", "content_metric_daily",
                "feedback_inbox", "publication", "approval_record", "content_version",
                "content_item", "run_checkpoint", "run_event", "campaign_run", "campaign",
                "app_user"}) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role,enabled) VALUES (1,'editor','x','EDITOR',TRUE)");
        jdbc.update("""
                INSERT INTO campaign(id,name,objective,audience,channels_json,constraints_json,
                                     status,created_by,version)
                VALUES (1,'c','o','a','[\"BLOG\",\"SOCIAL\"]','[]','DRAFT',1,0)
                """);
        jdbc.update("""
                INSERT INTO campaign_run(id,campaign_id,requested_policy,state,version)
                VALUES (2,1,'ORCHESTRATED','PUBLISHING',0)
                """);
        jdbc.update("""
                INSERT INTO content_item(id,run_id,task_id,current_version_no,version)
                VALUES (10,2,'create-blog',1,0)
                """);
        jdbc.update("""
                INSERT INTO content_version(id,content_item_id,version_no,content_json,
                                            source_refs_json,origin)
                VALUES (11,10,1,'{\"title\":\"T\",\"body\":\"hello\"}','[]','HUMAN')
                """);
        jdbc.update("""
                INSERT INTO approval_record(id,content_version_id,actor_id,comment_text)
                VALUES (1,11,1,'ok')
                """);
        jdbc.update("""
                INSERT INTO publication(id,run_id,content_version_id,approval_record_id,
                                        requested_by,channel,idempotency_key,status,
                                        next_attempt_at,version,external_post_id,published_at)
                VALUES (21,2,11,1,1,'BLOG',?,'PUBLISHED',UTC_TIMESTAMP(6),0,?,UTC_TIMESTAMP(6))
                """, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO content_metric_daily(publication_id,metric_date,views,clicks,likes)
                VALUES (21, '2026-08-13', 100, 12, 4)
                """);
        probe.reset();
    }

    @Test
    void candidateIsNotSearchableUntilApprovedAndIndexed() {
        var candidate = consolidation.generateCandidate(2L, 1L);
        assertThat(candidate.status()).isEqualTo(InsightStatus.PENDING);
        assertThat(query.searchApproved("短句 互动", null, 3)).isEmpty();

        var approved = review.decide(candidate.id(), InsightDecision.APPROVE, "ok", 1L);
        assertThat(approved.status()).isEqualTo(InsightStatus.APPROVED);
        assertThat(approved.indexStatus()).isEqualTo(InsightIndexStatus.INDEX_PENDING);

        worker.processBatch(Instant.now());

        assertThat(insightById(candidate.id()).indexStatus())
                .isEqualTo(InsightIndexStatus.INDEXED);
        var hits = query.searchApproved("短句 互动", null, 3);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().insightId()).isEqualTo(candidate.id());
        assertThat(hits.getFirst().sourceCampaignId()).isEqualTo(1L);
    }

    @Test
    void esFailureDoesNotRollBackTheHumanDecisionAndRecoversLater() {
        var candidate = consolidation.generateCandidate(2L, 1L);
        review.decide(candidate.id(), InsightDecision.APPROVE, "ok", 1L);
        probe.failNextIndex = true;

        worker.processBatch(Instant.now());

        var retried = insightById(candidate.id());
        assertThat(retried.status()).isEqualTo(InsightStatus.APPROVED);
        assertThat(retried.indexStatus()).isEqualTo(InsightIndexStatus.RETRY_WAIT);

        worker.processBatch(Instant.now().plusSeconds(60));
        assertThat(insightById(candidate.id()).indexStatus())
                .isEqualTo(InsightIndexStatus.INDEXED);
        assertThat(query.searchApproved("短句 互动", null, 3)).isNotEmpty();
    }

    @Test
    void rejectedCandidateIsNeverSearchable() {
        var candidate = consolidation.generateCandidate(2L, 1L);
        var rejected = review.decide(candidate.id(), InsightDecision.REJECT, "no", 1L);

        assertThat(rejected.status()).isEqualTo(InsightStatus.REJECTED);
        assertThat(rejected.indexStatus()).isEqualTo(InsightIndexStatus.NOT_INDEXED);
        worker.processBatch(Instant.now());
        assertThat(query.searchApproved("短句 互动", null, 3)).isEmpty();
    }

    @Test
    void sameDirectionReplayIsIdempotentAndConflictingDecisionIs409() {
        var candidate = consolidation.generateCandidate(2L, 1L);
        var approved = review.decide(candidate.id(), InsightDecision.APPROVE, "ok", 1L);
        var replayed = review.decide(candidate.id(), InsightDecision.APPROVE, "again", 1L);

        assertThat(replayed.reviewedBy()).isEqualTo(approved.reviewedBy());
        assertThat(insightById(candidate.id()).version()).isEqualTo(approved.version());

        assertThatThrownBy(() -> review.decide(candidate.id(), InsightDecision.REJECT,
                "changed mind", 1L))
                .isInstanceOf(InsightException.class)
                .satisfies(error -> assertThat(((InsightException) error).code())
                        .isEqualTo(InsightErrorCode.INSIGHT_DECISION_CONFLICT));
    }

    @Test
    void storeFailureMapsToSearchUnavailable() {
        probe.failAllSearches = true;
        assertThatThrownBy(() -> query.searchApproved("任何查询", null, 3))
                .isInstanceOf(InsightException.class)
                .satisfies(error -> assertThat(((InsightException) error).code())
                        .isEqualTo(InsightErrorCode.INSIGHT_SEARCH_UNAVAILABLE));
    }

    private CampaignInsight insightById(long id) {
        return insightRepository.findById(id).orElseThrow();
    }

    @TestConfiguration
    static class TestOverrides {

        @Bean("primaryModelPort")
        AgentModelPort scriptedPrimaryModel() {
            return new FakeModelAdapter(List.of(
                    FakeModelAdapter.Scene.of(VALID_INSIGHT_JSON),
                    FakeModelAdapter.Scene.of(VALID_INSIGHT_JSON),
                    FakeModelAdapter.Scene.of(VALID_INSIGHT_JSON),
                    FakeModelAdapter.Scene.of(VALID_INSIGHT_JSON),
                    FakeModelAdapter.Scene.of(VALID_INSIGHT_JSON)));
        }

        @Bean
        @Primary
        StoreProbe storeProbe(@Qualifier("insightSearchStore") InsightSearchStore real) {
            return new StoreProbe(real);
        }
    }

    static final class StoreProbe implements InsightSearchStore {

        private final InsightSearchStore delegate;
        volatile boolean failNextIndex;
        volatile boolean failAllSearches;

        StoreProbe(InsightSearchStore delegate) {
            this.delegate = delegate;
        }

        void reset() {
            failNextIndex = false;
            failAllSearches = false;
        }

        @Override
        public void ensureCompatibleIndex(com.pulseink.service.embedding.EmbeddingProfile profile) {
            delegate.ensureCompatibleIndex(profile);
        }

        @Override
        public void indexApproved(CampaignInsight insight) {
            if (failNextIndex) {
                failNextIndex = false;
                throw new IllegalStateException("simulated elasticsearch outage");
            }
            delegate.indexApproved(insight);
        }

        @Override
        public List<com.pulseink.service.memory.ApprovedInsightHit> search(
                String query, com.pulseink.domain.campaign.CampaignChannel channel, int topK) {
            if (failAllSearches) {
                throw new IllegalStateException("simulated elasticsearch outage");
            }
            return delegate.search(query, channel, topK);
        }
    }
}
