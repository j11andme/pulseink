package com.pulseink.repository.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.domain.feedback.FeedbackEvent;
import com.pulseink.service.feedback.FeedbackRepository;
import com.pulseink.support.BackendTestInfra;
import java.time.Instant;
import java.time.LocalDate;
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
class FeedbackRepositoryIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BackendTestInfra::datasourceUrl);
        registry.add("spring.datasource.username", BackendTestInfra::datasourceUsername);
        registry.add("spring.datasource.password", BackendTestInfra::datasourcePassword);
    }

    @Autowired FeedbackRepository repository;
    @Autowired JdbcTemplate jdbc;
    private long publicationId;

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
        long campaign = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO campaign_run(campaign_id,requested_policy,state,version) VALUES (?,'DIRECT','PUBLISHING',0)", campaign);
        long run = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO content_item(run_id,task_id,current_version_no,version) VALUES (?,'task',1,0)", run);
        long item = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO content_version(content_item_id,version_no,content_json,source_refs_json,origin) VALUES (?,1,'{}','[]','HUMAN')", item);
        long version = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO approval_record(content_version_id,actor_id,comment_text) VALUES (?,1,'ok')", version);
        long approval = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO publication(run_id,content_version_id,approval_record_id,requested_by,channel,idempotency_key,status,next_attempt_at,version) VALUES (?,?,?,?,? ,?,'PUBLISHED',UTC_TIMESTAMP(6),0)", run,version,approval,1,"BLOG",UUID.randomUUID().toString());
        publicationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void duplicateInboxEventDoesNotAccumulateTwice() {
        UUID eventId = UUID.randomUUID();
        var event = new FeedbackEvent(1, eventId, "CHANNEL_METRICS_RECORDED",
                Instant.parse("2026-08-13T12:01:00Z"), UUID.randomUUID(), publicationId,
                1L, "BLOG", LocalDate.of(2026, 8, 13), 100, 12, 4);
        assertThat(repository.ingest(event, "raw", 0, 1)).isTrue();
        assertThat(repository.ingest(event, "raw", 0, 2)).isFalse();
        assertThat(repository.findByRunId(repository.findByPublicationId(publicationId).runId()))
                .singleElement().satisfies(metric -> {
                    assertThat(metric.views()).isEqualTo(100);
                    assertThat(metric.clicks()).isEqualTo(12);
                    assertThat(metric.likes()).isEqualTo(4);
                });
    }
}
