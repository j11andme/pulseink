package com.pulseink.controller.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.feedback.FeedbackEvent;
import com.pulseink.service.feedback.FeedbackIngestionService;
import com.pulseink.service.feedback.InvalidFeedbackException;
import com.pulseink.support.BackendTestInfra;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
class DuplicateFeedbackIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BackendTestInfra::datasourceUrl);
        registry.add("spring.datasource.username", BackendTestInfra::datasourceUsername);
        registry.add("spring.datasource.password", BackendTestInfra::datasourcePassword);
    }

    @Autowired FeedbackIngestionService ingestion;
    @Autowired JdbcTemplate jdbc;
    private long publicationId;

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[] {"content_metric_daily", "feedback_inbox", "publication",
                "approval_record", "content_version", "content_item", "campaign_run", "campaign",
                "app_user"}) {
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
        jdbc.update("""
                INSERT INTO publication(run_id,content_version_id,approval_record_id,requested_by,
                                        channel,idempotency_key,status,next_attempt_at,version)
                VALUES (?,?,?,1,'BLOG',?,'PUBLISHED',UTC_TIMESTAMP(6),0)
                """, run, version, approval, UUID.randomUUID().toString());
        publicationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void firstEventInsertsInboxAndAppliesMetrics() {
        var event = event(UUID.randomUUID(), 100, 12, 4);

        boolean applied = ingestion.consume(event, "raw", 0, 1);

        assertThat(applied).isTrue();
        assertThat(inboxCount(event.eventId())).isEqualTo(1);
        assertThat(metrics()).isEqualTo("100,12,4");
    }

    @Test
    void duplicateEventIdDoesNotAccumulateTwice() {
        var event = event(UUID.randomUUID(), 100, 12, 4);

        assertThat(ingestion.consume(event, "raw", 0, 1)).isTrue();
        assertThat(ingestion.consume(event, "raw", 0, 2)).isFalse();

        assertThat(inboxCount(event.eventId())).isEqualTo(1);
        assertThat(metrics()).isEqualTo("100,12,4");
    }

    @Test
    void differentEventsAccumulateDeltas() {
        assertThat(ingestion.consume(event(UUID.randomUUID(), 100, 12, 4), "raw", 0, 1)).isTrue();
        assertThat(ingestion.consume(event(UUID.randomUUID(), 40, 3, 1), "raw", 0, 2)).isTrue();

        assertThat(metrics()).isEqualTo("140,15,5");
    }

    @Test
    void metricFailureRollsBackTheInboxRow() {
        var event = event(UUID.randomUUID(), 100, 12, 4);
        jdbc.execute("RENAME TABLE content_metric_daily TO content_metric_daily_bak");
        try {
            assertThatThrownBy(() -> ingestion.consume(event, "raw", 0, 1))
                    .isInstanceOf(DataAccessException.class);
            assertThat(inboxCount(event.eventId())).isZero();
        } finally {
            jdbc.execute("RENAME TABLE content_metric_daily_bak TO content_metric_daily");
        }
    }

    @Test
    void unknownPublicationIsRejectedStably() {
        var event = event(UUID.randomUUID(), 100, 12, 4);
        var unknown = new FeedbackEvent(1, event.eventId(), "CHANNEL_METRICS_RECORDED",
                event.occurredAt(), event.externalPostId(), 999_999L, event.contentVersionId(),
                event.channel(), event.metricDate(), 100, 12, 4);

        assertThatThrownBy(() -> ingestion.consume(unknown, "raw", 0, 1))
                .isInstanceOf(InvalidFeedbackException.class)
                .hasMessageContaining("999999");
        assertThat(inboxCount(event.eventId())).isZero();
    }

    @Test
    void unknownSchemaVersionIsRejected() {
        var event = event(UUID.randomUUID(), 100, 12, 4);
        var unknown = new FeedbackEvent(99, event.eventId(), "CHANNEL_METRICS_RECORDED",
                event.occurredAt(), event.externalPostId(), publicationId,
                event.contentVersionId(), event.channel(), event.metricDate(), 100, 12, 4);

        assertThatThrownBy(() -> ingestion.consume(unknown, "raw", 0, 1))
                .isInstanceOf(InvalidFeedbackException.class)
                .hasMessageContaining("schema version");
        assertThat(inboxCount(event.eventId())).isZero();
    }

    @Test
    void negativeCountersAreRejected() {
        var event = event(UUID.randomUUID(), -1, 12, 4);

        assertThatThrownBy(() -> ingestion.consume(event, "raw", 0, 1))
                .isInstanceOf(InvalidFeedbackException.class)
                .hasMessageContaining("non-negative");
        assertThat(inboxCount(event.eventId())).isZero();
    }

    @Test
    void metricDateMustMatchTheBusinessZone() {
        var event = event(UUID.randomUUID(), 100, 12, 4);
        var wrongDate = new FeedbackEvent(1, event.eventId(), "CHANNEL_METRICS_RECORDED",
                event.occurredAt(), event.externalPostId(), publicationId,
                event.contentVersionId(), event.channel(), LocalDate.of(2026, 8, 14),
                100, 12, 4);

        assertThatThrownBy(() -> ingestion.consume(wrongDate, "raw", 0, 1))
                .isInstanceOf(InvalidFeedbackException.class)
                .hasMessageContaining("metricDate");
    }

    private FeedbackEvent event(UUID eventId, long views, long clicks, long likes) {
        return new FeedbackEvent(1, eventId, "CHANNEL_METRICS_RECORDED",
                Instant.parse("2026-08-13T12:01:00Z"), UUID.randomUUID(), publicationId,
                1L, "BLOG", LocalDate.of(2026, 8, 13), views, clicks, likes);
    }

    private int inboxCount(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM feedback_inbox WHERE event_id = ?",
                Integer.class, eventId.toString());
    }

    private String metrics() {
        return jdbc.query("""
                        SELECT CONCAT(views, ',', clicks, ',', likes)
                        FROM content_metric_daily WHERE publication_id = ?
                        """, (resultSet, rowNum) -> resultSet.getString(1), publicationId)
                .stream().findFirst().orElse(null);
    }
}
