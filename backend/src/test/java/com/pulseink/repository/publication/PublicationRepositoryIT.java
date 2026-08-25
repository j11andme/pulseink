package com.pulseink.repository.publication;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.publication.Publication;
import com.pulseink.domain.publication.PublicationStatus;
import com.pulseink.domain.publication.PublishReceipt;
import com.pulseink.service.publishing.PublicationRepository;
import com.pulseink.support.BackendTestInfra;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake",
        "pulseink.publication.worker-enabled=false",
        "pulseink.feedback.consumer-enabled=false"
})
class PublicationRepositoryIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BackendTestInfra::datasourceUrl);
        registry.add("spring.datasource.username", BackendTestInfra::datasourceUsername);
        registry.add("spring.datasource.password", BackendTestInfra::datasourcePassword);
    }

    @Autowired PublicationRepository repository;
    @Autowired JdbcTemplate jdbc;

    private long runId;
    private long contentVersionId;
    private long approvalId;

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[] {"content_metric_daily", "feedback_inbox", "publication",
                "approval_record", "content_version", "content_item", "campaign_run", "campaign", "app_user"}) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role,enabled) VALUES (1,'editor','x','EDITOR',TRUE)");
        jdbc.update("INSERT INTO campaign(name,objective,audience,channels_json,constraints_json,status,created_by,version) VALUES ('c','o','a','[\"BLOG\"]','[]','DRAFT',1,0)");
        long campaignId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO campaign_run(campaign_id,requested_policy,state,version) VALUES (?,'DIRECT','WAITING_APPROVAL',0)", campaignId);
        runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO content_item(run_id,task_id,current_version_no,version) VALUES (?,'create-blog',1,0)", runId);
        long contentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO content_version(content_item_id,version_no,content_json,source_refs_json,origin) VALUES (?,1,'{\"body\":\"hello\"}','[]','HUMAN')", contentId);
        contentVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO approval_record(content_version_id,actor_id,comment_text) VALUES (?,1,'ok')", contentVersionId);
        approvalId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void createIsIdempotentAndCompetingClaimsUseCas() {
        var first = repository.createOrGet(pending("BLOG"));
        var second = repository.createOrGet(pending("BLOG"));
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.idempotencyKey()).isEqualTo(first.idempotencyKey());

        var claimed = repository.claimDue(Instant.parse("2026-08-13T12:00:00Z"), 10);
        assertThat(claimed).singleElement().satisfies(value -> {
            assertThat(value.status()).isEqualTo(PublicationStatus.SENDING);
            assertThat(repository.claim(first.id(), first.version())).isFalse();
        });
    }

    @Test
    void terminalUpdatesRequireExpectedVersion() {
        var pending = repository.createOrGet(pending("BLOG"));
        assertThat(repository.claim(pending.id(), pending.version())).isTrue();
        var sending = repository.findById(pending.id()).orElseThrow();
        var receipt = new PublishReceipt(UUID.randomUUID(), sending.idempotencyKey(),
                CampaignChannel.BLOG, Instant.parse("2026-08-13T12:01:00Z"), false);

        assertThat(repository.markPublished(sending.id(), sending.version() - 1, receipt)).isFalse();
        assertThat(repository.markPublished(sending.id(), sending.version(), receipt)).isTrue();
        assertThat(repository.findById(sending.id()).orElseThrow().status())
                .isEqualTo(PublicationStatus.PUBLISHED);
    }

    private Publication pending(String channel) {
        return Publication.pending(runId, contentVersionId, approvalId, 1L,
                CampaignChannel.valueOf(channel), UUID.randomUUID(),
                Instant.parse("2026-08-13T12:00:00Z"));
    }
}
