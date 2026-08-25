package com.pulseink.integration.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.memory.ContextAssembler;
import com.pulseink.agent.memory.ContextAssemblyRequest;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.service.memory.ConsolidateInsightUseCase;
import com.pulseink.service.memory.InsightDecision;
import com.pulseink.service.memory.InsightIndexWorker;
import com.pulseink.service.memory.ReviewInsightUseCase;
import com.pulseink.service.memory.RunWorkingMemoryCache;
import com.pulseink.support.MemoryTestContainers;
import com.pulseink.support.MemoryElasticsearchTestContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end context assembly over real MySQL, Redis and Elasticsearch: an approved and
 * indexed insight plus the episodic projection must reach a CREATOR role context, and the
 * working memory cache must rebuild identically after the Redis key is deleted.
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
class ContextAssemblerIntegrationTest {

    private static final String VALID_INSIGHT_JSON = """
            {"schemaVersion":1,"category":"CHANNEL_PATTERN",
             "title":"集成上下文洞察标记","insightText":"短句形式能提升互动",
             "scopeType":"WORKSPACE","scopeValue":"",
             "applicableChannels":["BLOG"],
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
        registry.add("spring.data.redis.url", MemoryTestContainers::redisUrl);
        registry.add("pulseink.memory.index-alias",
                () -> "pulseink-memory-context-it-" + UUID.randomUUID().toString()
                        .substring(0, 8));
    }

    @Autowired ContextAssembler assembler;
    @Autowired ConsolidateInsightUseCase consolidation;
    @Autowired ReviewInsightUseCase review;
    @Autowired InsightIndexWorker worker;
    @Autowired RunWorkingMemoryCache cache;
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
        jdbc.update("""
                INSERT INTO app_user(id,username,password_hash,role,enabled)
                VALUES (1,'editor','x','EDITOR',TRUE)
                """);
        jdbc.update("""
                INSERT INTO campaign(id,name,objective,audience,channels_json,constraints_json,
                                     status,created_by,version)
                VALUES (1,'c','o','a','[\"BLOG\"]','[]','DRAFT',1,0)
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
        cache.invalidate(2L);
    }

    @Test
    void approvedInsightAndEpisodicFactsReachTheStrategistContext() {
        var candidate = consolidation.generateCandidate(2L, 1L);
        review.decide(candidate.id(), InsightDecision.APPROVE, "ok", 1L);
        worker.processBatch(Instant.now());

        var context = assembler.assemble(new ContextAssemblyRequest(
                2L, strategistProfile(),
                "objective=短句形式能提升互动; audience=a; channels=[BLOG]",
                "制定内容策略", List.of(), List.of(CampaignChannel.BLOG), 12_000));

        assertThat(context.renderedText())
                .contains("[EPISODIC_SUMMARY]")
                .contains("campaignId=1")
                .contains("approvedVersions=1")
                .contains("publishedPosts=1")
                .contains("[APPROVED_INSIGHTS]")
                .contains("集成上下文洞察标记")
                .contains("insightId=" + candidate.id())
                .contains("sourceCampaignId=1");
    }

    @Test
    void workingMemorySurvivesCacheDeletionWithIdenticalSemantics() {
        var first = assembler.assemble(new ContextAssemblyRequest(
                2L, unifiedProfile(), "brief", "objective", List.of(),
                List.of(CampaignChannel.BLOG), 12_000));
        assertThat(first.workingMemoryCacheHit()).isFalse();

        var cached = assembler.assemble(new ContextAssemblyRequest(
                2L, unifiedProfile(), "brief", "objective", List.of(),
                List.of(CampaignChannel.BLOG), 12_000));
        assertThat(cached.workingMemoryCacheHit()).isTrue();

        cache.invalidate(2L);
        var rebuilt = assembler.assemble(new ContextAssemblyRequest(
                2L, unifiedProfile(), "brief", "objective", List.of(),
                List.of(CampaignChannel.BLOG), 12_000));
        assertThat(rebuilt.workingMemoryCacheHit()).isFalse();
        assertThat(rebuilt.renderedText()).isEqualTo(cached.renderedText());
    }

    private static AgentProfile strategistProfile() {
        return AgentProfile.role("strategist-it", AgentRole.STRATEGIST, Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                "system prompt",
                Set.of(com.pulseink.agent.artifact.ArtifactType.CONTENT_STRATEGY), 1, 1, 1);
    }

    private static AgentProfile unifiedProfile() {
        return AgentProfile.unified("unified-it", Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));
    }

    @TestConfiguration
    static class TestOverrides {

        @Bean("primaryModelPort")
        AgentModelPort scriptedPrimaryModel() {
            return new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of(VALID_INSIGHT_JSON)));
        }
    }
}
