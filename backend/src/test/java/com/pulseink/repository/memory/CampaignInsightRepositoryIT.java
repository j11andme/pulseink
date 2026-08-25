package com.pulseink.repository.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.CampaignInsight;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightEvidenceRef;
import com.pulseink.domain.memory.InsightIndexStatus;
import com.pulseink.domain.memory.InsightScopeType;
import com.pulseink.domain.memory.InsightStatus;
import com.pulseink.service.memory.CampaignInsightRepository;
import com.pulseink.support.MemoryTestContainers;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
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
class CampaignInsightRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MemoryTestContainers::mysqlUrl);
        registry.add("spring.datasource.username", MemoryTestContainers::mysqlUsername);
        registry.add("spring.datasource.password", MemoryTestContainers::mysqlPassword);
    }

    @Autowired CampaignInsightRepository repository;
    @Autowired JdbcTemplate jdbc;

    private long campaignId;
    private long runId;
    private String snapshotHash = "b".repeat(64);

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
                INSERT INTO campaign(name,objective,audience,channels_json,constraints_json,
                                     status,created_by,version)
                VALUES ('c','o','a','[\"BLOG\"]','[]','DRAFT',1,0)
                """);
        campaignId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO campaign_run(campaign_id,requested_policy,state,version)
                VALUES (?,'DIRECT','RUNNING',0)
                """, campaignId);
        runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void insertRoundTripAndSnapshotLookup() {
        var inserted = repository.insertPending(pending(snapshotHash));

        assertThat(inserted.id()).isPositive();
        assertThat(inserted.status()).isEqualTo(InsightStatus.PENDING);
        assertThat(inserted.indexStatus()).isEqualTo(InsightIndexStatus.NOT_INDEXED);
        assertThat(repository.findById(inserted.id())).get()
                .extracting(CampaignInsight::title).isEqualTo("标题");
        assertThat(repository.findBySnapshot(runId, snapshotHash, "insight-v1")).isPresent();
        assertThat(repository.findBySnapshot(runId, "c".repeat(64), "insight-v1")).isEmpty();
    }

    @Test
    void concurrentInsertOfTheSameSnapshotKeepsExactlyOneRow() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(this::insertPendingOrDuplicate);
            Future<Object> second = executor.submit(this::insertPendingOrDuplicate);
            var results = List.of(first.get(), second.get());

            assertThat(results.stream().filter(CampaignInsight.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(results.stream().filter(DuplicateKeyException.class::isInstance).count())
                    .isEqualTo(1);
            assertThat(results.stream()
                    .filter(CampaignInsight.class::isInstance)
                    .map(CampaignInsight.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .id()).isPositive();
        }
        assertThat(countInsights()).isEqualTo(1);
    }

    @Test
    void onlyOneConcurrentDecisionSucceedsFromPending() throws Exception {
        var inserted = repository.insertPending(pending(snapshotHash));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CampaignInsight> approve = executor.submit(() -> repository.decidePending(
                    inserted.id(), inserted.version(), InsightStatus.APPROVED,
                    "ok", 1L, NOW.plusSeconds(1)));
            Future<CampaignInsight> reject = executor.submit(() -> repository.decidePending(
                    inserted.id(), inserted.version(), InsightStatus.REJECTED,
                    "no", 1L, NOW.plusSeconds(1)));
            var winner = approve.get();
            assertThatThrownBy(reject::get)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(winner.status()).isEqualTo(InsightStatus.APPROVED);
            assertThat(winner.indexStatus()).isEqualTo(InsightIndexStatus.INDEX_PENDING);
            assertThat(winner.reviewedBy()).isEqualTo(1L);
        }
        assertThat(repository.findById(inserted.id()).orElseThrow().status())
                .isEqualTo(InsightStatus.APPROVED);
    }

    @Test
    void decisionAfterTerminalStateIsRejected() {
        var inserted = repository.insertPending(pending(snapshotHash));
        var approved = repository.decidePending(inserted.id(), inserted.version(),
                InsightStatus.APPROVED, "ok", 1L, NOW.plusSeconds(1));

        assertThatThrownBy(() -> repository.decidePending(approved.id(), approved.version(),
                InsightStatus.REJECTED, "changed", 1L, NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void indexClaimAndTerminalTransitionsAreVersionGuarded() {
        var inserted = repository.insertPending(pending(snapshotHash));
        var approved = repository.decidePending(inserted.id(), inserted.version(),
                InsightStatus.APPROVED, "ok", 1L, NOW.plusSeconds(1));

        var claimed = repository.claimIndexDue(NOW.plusSeconds(2), 10);
        assertThat(claimed).singleElement().satisfies(insight -> {
            assertThat(insight.id()).isEqualTo(approved.id());
            assertThat(insight.indexStatus()).isEqualTo(InsightIndexStatus.INDEXING);
            assertThat(insight.indexAttempts()).isEqualTo(1);
        });

        assertThat(repository.markIndexed(claimed.getFirst().id(),
                claimed.getFirst().version() - 1, NOW.plusSeconds(3))).isFalse();
        assertThat(repository.markIndexed(claimed.getFirst().id(),
                claimed.getFirst().version(), NOW.plusSeconds(3))).isTrue();
        assertThat(repository.findById(approved.id()).orElseThrow().indexStatus())
                .isEqualTo(InsightIndexStatus.INDEXED);

        // Retry/failed paths need a fresh APPROVED row.
        var second = repository.insertPending(pending("d".repeat(64)));
        var secondApproved = repository.decidePending(second.id(), second.version(),
                InsightStatus.APPROVED, "ok", 1L, NOW.plusSeconds(4));
        var secondClaimed = repository.claimIndexDue(NOW.plusSeconds(5), 10).stream()
                .filter(insight -> insight.id() == secondApproved.id()).findFirst().orElseThrow();
        assertThat(repository.markIndexRetry(secondClaimed.id(), secondClaimed.version(),
                NOW.plusSeconds(60), "es down")).isTrue();
        assertThat(repository.findById(second.id()).orElseThrow().indexStatus())
                .isEqualTo(InsightIndexStatus.RETRY_WAIT);

        var reclaimed = repository.claimIndexDue(NOW.plusSeconds(61), 10).stream()
                .filter(insight -> insight.id() == second.id()).findFirst().orElseThrow();
        assertThat(repository.markIndexFailed(reclaimed.id(), reclaimed.version(),
                "attempts exhausted")).isTrue();
        assertThat(repository.findById(second.id()).orElseThrow().indexStatus())
                .isEqualTo(InsightIndexStatus.FAILED);
    }

    @Test
    void indexClaimSkipsPendingAndRejectedRows() {
        var pending = repository.insertPending(pending(snapshotHash));
        var rejected = repository.decidePending(pending.id(), pending.version(),
                InsightStatus.REJECTED, "no", 1L, NOW.plusSeconds(1));

        assertThat(repository.claimIndexDue(NOW.plusSeconds(2), 10)).isEmpty();
        assertThat(repository.findById(rejected.id()).orElseThrow().indexStatus())
                .isEqualTo(InsightIndexStatus.NOT_INDEXED);
    }

    @Test
    void listByCampaignOrdersByCreationDescending() {
        var first = repository.insertPending(pending(snapshotHash));
        var second = repository.insertPending(pending("e".repeat(64)));

        assertThat(repository.findByCampaign(campaignId))
                .extracting(CampaignInsight::id)
                .containsExactly(second.id(), first.id());
    }

    private CampaignInsight pending(String hash) {
        return CampaignInsight.pending(
                campaignId, runId, InsightCategory.CHANNEL_PATTERN, "标题", "正文结论",
                InsightScopeType.CHANNEL, "SOCIAL", List.of(CampaignChannel.SOCIAL),
                List.of(new InsightEvidenceRef(11L, 21L,
                        LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 7))),
                0.78, List.of("样本窗口较短"), hash, "insight-v1", 1L, NOW);
    }

    private Object insertPendingOrDuplicate() {
        try {
            return repository.insertPending(pending(snapshotHash));
        } catch (DuplicateKeyException duplicate) {
            return duplicate;
        }
    }

    private int countInsights() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM campaign_insight", Integer.class);
    }
}
