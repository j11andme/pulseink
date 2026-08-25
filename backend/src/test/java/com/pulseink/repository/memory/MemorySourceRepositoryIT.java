package com.pulseink.repository.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.service.memory.InsightException;
import com.pulseink.service.memory.InsightErrorCode;
import com.pulseink.service.memory.InsightSourceSnapshot;
import com.pulseink.service.memory.MemorySourceRepository;
import com.pulseink.support.MemoryTestContainers;
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
        "pulseink.feedback.consumer-enabled=false",
        "pulseink.memory.index-worker-enabled=false",
        "pulseink.run-lease.enabled=false"
})
class MemorySourceRepositoryIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MemoryTestContainers::mysqlUrl);
        registry.add("spring.datasource.username", MemoryTestContainers::mysqlUsername);
        registry.add("spring.datasource.password", MemoryTestContainers::mysqlPassword);
    }

    @Autowired MemorySourceRepository repository;
    @Autowired JdbcTemplate jdbc;

    private long campaignId;
    private long runId;
    private long contentVersionId;
    private long publicationId;

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[] {"campaign_insight", "content_metric_daily",
                "feedback_inbox", "publication", "approval_record", "review_issue",
                "review_report", "content_version", "content_item", "run_checkpoint",
                "run_event", "campaign_run", "campaign", "app_user"}) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role,enabled) VALUES (1,'editor','x','EDITOR',TRUE)");
        jdbc.update("""
                INSERT INTO campaign(name,objective,audience,channels_json,constraints_json,
                                     status,created_by,version)
                VALUES ('c','o','a','[\"BLOG\",\"SOCIAL\"]','[]','DRAFT',1,0)
                """);
        campaignId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO campaign_run(campaign_id,requested_policy,state,version)
                VALUES (?,'ORCHESTRATED','PUBLISHING',0)
                """, campaignId);
        runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        seedContentAndPublication(true, true, true);
    }

    @Test
    void eligibleSnapshotContainsApprovedVersionPublicationAndMetrics() {
        var snapshot = repository.loadEligibleSnapshot(runId);

        assertThat(snapshot.runId()).isEqualTo(runId);
        assertThat(snapshot.campaignId()).isEqualTo(campaignId);
        assertThat(snapshot.approvedVersions()).singleElement().satisfies(version -> {
            assertThat(version.contentVersionId()).isEqualTo(contentVersionId);
            assertThat(version.content()).containsEntry("body", "hello");
            assertThat(version.origin()).isEqualTo("HUMAN");
            assertThat(version.approvedBy()).isEqualTo(1L);
            assertThat(version.approvalComment()).isEqualTo("ok");
        });
        assertThat(snapshot.publications()).singleElement().satisfies(post -> {
            assertThat(post.publicationId()).isEqualTo(publicationId);
            assertThat(post.channel()).isEqualTo(com.pulseink.domain.campaign.CampaignChannel.BLOG);
            assertThat(post.receipt()).containsEntry("provider", "sandbox");
        });
        assertThat(snapshot.metrics()).singleElement().satisfies(metric ->
                assertThat(metric.views()).isEqualTo(100));
        assertThat(snapshot.sourceSnapshotHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void snapshotIsDeterministicWithStableOrdering() {
        // Additional facts inserted out of natural order must not change ordering semantics.
        jdbc.update("""
                INSERT INTO content_metric_daily(publication_id,metric_date,views,clicks,likes)
                VALUES (?, '2026-08-07', 40, 3, 1)
                """, publicationId);
        jdbc.update("""
                INSERT INTO content_metric_daily(publication_id,metric_date,views,clicks,likes)
                VALUES (?, '2026-08-06', 60, 9, 3)
                """, publicationId);

        var first = repository.loadEligibleSnapshot(runId);
        var second = repository.loadEligibleSnapshot(runId);

        assertThat(first.sourceSnapshotHash()).isEqualTo(second.sourceSnapshotHash());
        assertThat(first.metrics()).extracting(InsightSourceSnapshot.MetricWindow::metricDate)
                .containsExactly(
                        java.time.LocalDate.of(2026, 8, 6),
                        java.time.LocalDate.of(2026, 8, 7),
                        java.time.LocalDate.of(2026, 8, 13));
    }

    @Test
    void snapshotHashChangesWhenMetricsOrContentChange() {
        var baseline = repository.loadEligibleSnapshot(runId).sourceSnapshotHash();

        jdbc.update("""
                UPDATE content_metric_daily SET views = 999 WHERE publication_id = ?
                """, publicationId);
        assertThat(repository.loadEligibleSnapshot(runId).sourceSnapshotHash())
                .isNotEqualTo(baseline);

        jdbc.update("UPDATE content_metric_daily SET views = 100 WHERE publication_id = ?",
                publicationId);
        jdbc.update("""
                UPDATE content_version SET content_json = '{\"body\":\"changed\"}'
                WHERE id = ?
                """, contentVersionId);
        assertThat(repository.loadEligibleSnapshot(runId).sourceSnapshotHash())
                .isNotEqualTo(baseline);
    }

    @Test
    void snapshotUsesTheApprovedVersionActuallyPublishedNotANewerCurrentDraft() {
        long itemId = jdbc.queryForObject(
                "SELECT content_item_id FROM content_version WHERE id = ?",
                Long.class, contentVersionId);
        jdbc.update("""
                INSERT INTO content_version(content_item_id,version_no,content_json,
                                            source_refs_json,origin,created_by)
                VALUES (?,2,'{\"title\":\"new draft\",\"body\":\"not published\"}',
                        '[]','HUMAN',1)
                """, itemId);
        long newerVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE content_item SET current_version_no = 2 WHERE id = ?", itemId);

        var snapshot = repository.loadEligibleSnapshot(runId);

        assertThat(snapshot.approvedVersions())
                .extracting(InsightSourceSnapshot.ApprovedVersion::contentVersionId)
                .containsExactly(contentVersionId)
                .doesNotContain(newerVersionId);
        assertThat(snapshot.publications()).singleElement().satisfies(post ->
                assertThat(post.contentVersionId()).isEqualTo(contentVersionId));
    }

    @Test
    void missingApprovalIsNotEligible() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        jdbc.execute("DELETE FROM approval_record");
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        assertNotReady();
    }

    @Test
    void unpublishedPublicationIsNotEligible() {
        jdbc.update("UPDATE publication SET status = 'PENDING' WHERE id = ?", publicationId);
        assertNotReady();
    }

    @Test
    void missingMetricsIsNotEligible() {
        jdbc.execute("DELETE FROM content_metric_daily");
        assertNotReady();
    }

    @Test
    void flywayAppliesV1ThroughV6OnAFreshDatabase() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE",
                Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(6);
    }

    private void assertNotReady() {
        assertThatThrownBy(() -> repository.loadEligibleSnapshot(runId))
                .isInstanceOf(InsightException.class)
                .satisfies(error -> assertThat(((InsightException) error).code())
                        .isEqualTo(InsightErrorCode.INSIGHT_SOURCE_NOT_READY));
    }

    private void seedContentAndPublication(boolean approved, boolean published,
                                           boolean withMetrics) {
        jdbc.update("""
                INSERT INTO content_item(run_id,task_id,current_version_no,version)
                VALUES (?,'create-blog',1,0)
                """, runId);
        long contentItemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO content_version(content_item_id,version_no,content_json,
                                            source_refs_json,origin)
                VALUES (?,1,'{\"title\":\"T\",\"body\":\"hello\"}','[]','HUMAN')
                """, contentItemId);
        contentVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (approved) {
            jdbc.update("""
                    INSERT INTO approval_record(content_version_id,actor_id,comment_text)
                    VALUES (?,1,'ok')
                    """, contentVersionId);
        } else {
            return;
        }
        long approvalId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO publication(run_id,content_version_id,approval_record_id,requested_by,
                                        channel,idempotency_key,status,next_attempt_at,version,
                                        external_post_id,receipt_json,published_at)
                VALUES (?,?,?,1,'BLOG',?,?,UTC_TIMESTAMP(6),0,?,
                        '{"provider":"sandbox","accepted":true}',UTC_TIMESTAMP(6))
                """, runId, contentVersionId, approvalId, UUID.randomUUID().toString(),
                published ? "PUBLISHED" : "PENDING", UUID.randomUUID().toString());
        publicationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (withMetrics) {
            jdbc.update("""
                    INSERT INTO content_metric_daily(publication_id,metric_date,views,clicks,likes)
                    VALUES (?, '2026-08-13', 100, 12, 4)
                    """, publicationId);
        }
    }
}
