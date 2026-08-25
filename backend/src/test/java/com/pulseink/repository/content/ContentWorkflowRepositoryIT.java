package com.pulseink.repository.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.domain.content.ContentOrigin;
import com.pulseink.domain.content.ReviewAssessment;
import com.pulseink.domain.content.ReviewIssue;
import com.pulseink.domain.content.ReviewIssueType;
import com.pulseink.service.content.ContentWorkflowRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake"
})
class ContentWorkflowRepositoryIT {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.7")
            .withDatabaseName("pulseink")
            .withUsername("pulseink")
            .withPassword("pulseink_dev");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ContentWorkflowRepository repository;

    private long runId;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("DELETE FROM approval_record");
        jdbc.execute("DELETE FROM review_issue");
        jdbc.execute("DELETE FROM review_report");
        jdbc.execute("DELETE FROM content_version");
        jdbc.execute("DELETE FROM content_item");
        jdbc.execute("DELETE FROM run_checkpoint");
        jdbc.execute("DELETE FROM run_event");
        jdbc.execute("DELETE FROM campaign_run");
        jdbc.execute("DELETE FROM campaign");
        jdbc.execute("DELETE FROM app_user");
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, role, enabled)
                VALUES (1, 'editor', 'hash', 'EDITOR', TRUE)
                """);
        jdbc.update("""
                INSERT INTO campaign
                    (name, objective, audience, channels_json, constraints_json,
                     status, created_by, version)
                VALUES ('Campaign', 'objective', 'audience', '[\"BLOG\"]', '[]',
                        'DRAFT', 1, 0)
                """);
        long campaignId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO campaign_run
                    (campaign_id, requested_policy, selected_mode, state, version)
                VALUES (?, 'ORCHESTRATED', 'ORCHESTRATED', 'WAITING_APPROVAL', 0)
                """, campaignId);
        runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void agentDraftAndReviewCaptureAreIdempotent() {
        var draft = draft("draft-1", 1, "first");
        repository.captureAgentVersion(runId, "create-blog", draft);
        repository.captureAgentVersion(runId, "create-blog", draft);

        var review = review("review-1", 1);
        var assessment = new ReviewAssessment(false, List.of(
                new ReviewIssue(ReviewIssueType.STYLE,
                        Set.of("create-blog"), "tone")));
        repository.captureReview(runId, review, assessment, 0);
        repository.captureReview(runId, review, assessment, 0);

        var item = repository.findByRunId(runId).getFirst();
        assertThat(item.versions()).hasSize(1);
        assertThat(item.versions().getFirst().origin()).isEqualTo(ContentOrigin.AGENT);
        assertThat(item.currentVersionNo()).isEqualTo(1);
        assertThat(repository.findReviewsByRunId(runId)).singleElement().satisfies(report ->
                assertThat(report.issues()).singleElement().satisfies(issue ->
                        assertThat(issue.affectedTaskIds()).containsExactly("create-blog")));
    }

    @Test
    void appendingHumanVersionKeepsOldVersionImmutableAndRejectsStaleCas() {
        repository.captureAgentVersion(runId, "create-blog", draft("draft-1", 1, "first"));
        var item = repository.findByRunId(runId).getFirst();

        var second = repository.appendHumanVersion(
                item.id(), 1, item.version(), Map.of("body", "second"),
                List.of("source-2"), 1L);

        var reloaded = repository.findById(item.id()).orElseThrow();
        assertThat(second.versionNo()).isEqualTo(2);
        assertThat(reloaded.currentVersionNo()).isEqualTo(2);
        assertThat(reloaded.versions()).extracting(v -> v.content().get("body"))
                .containsExactly("first", "second");
        assertThatThrownBy(() -> repository.appendHumanVersion(
                item.id(), 1, item.version(), Map.of("body", "stale"),
                List.of(), 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentApprovalOfSameVersionCreatesOneRecord() throws Exception {
        repository.captureAgentVersion(runId, "create-blog", draft("draft-1", 1, "first"));
        var item = repository.findByRunId(runId).getFirst();
        var version = item.versions().getFirst();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var successes = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    start.await();
                    repository.approve(item.id(), version.id(), 1,
                            item.version(), "approved", 1L);
                    successes.incrementAndGet();
                } catch (RuntimeException ignored) {
                    // One contender must lose the optimistic-lock/unique-key race.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successes).hasValue(1);
        assertThat(repository.findById(item.id()).orElseThrow().approvals()).hasSize(1);
    }

    private AgentArtifact draft(String id, int version, String body) {
        return AgentArtifact.create(id, runId, "create-blog", ArtifactType.CONTENT_DRAFT,
                version, Map.of("body", body), List.of("source-1"), Instant.now());
    }

    private AgentArtifact review(String id, int version) {
        return AgentArtifact.create(id, runId, "review", ArtifactType.REVIEW_REPORT,
                version, Map.of("passed", false, "issues", List.of()),
                List.of(), Instant.now());
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
