package com.pulseink.repository.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.checkpoint.RunCheckpoint;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamHandle;
import com.pulseink.agent.react.DirectAgentEngine;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.react.UnifiedAgentRunner;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.execution.ExecutionDecision;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.service.campaign.RunEvent;
import com.pulseink.service.campaign.RunEventService;
import com.pulseink.service.campaign.RunEventType;
import com.pulseink.service.campaign.RunExecutionService;
import java.time.Duration;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest(properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake"
})
class MybatisRunJournalIT {

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.pulseink.service.campaign.RunRepository runRepository;

    @Autowired
    private com.pulseink.service.campaign.CampaignRepository campaignRepository;

    @Autowired
    private MybatisRunJournal journal;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM approval_record");
        jdbcTemplate.execute("DELETE FROM review_issue");
        jdbcTemplate.execute("DELETE FROM review_report");
        jdbcTemplate.execute("DELETE FROM content_version");
        jdbcTemplate.execute("DELETE FROM content_item");
        jdbcTemplate.execute("DELETE FROM run_checkpoint");
        jdbcTemplate.execute("DELETE FROM run_event");
        jdbcTemplate.execute("DELETE FROM campaign_run");
        jdbcTemplate.execute("DELETE FROM campaign");
    }

    @Test
    void sequencesAreMonotonicOneTwoThree() {

        var runId = insertRun(ExecutionMode.DIRECT);

        var e1 = journal.appendEvent(runId, RunEventType.EXECUTION_MODE_SELECTED, Map.of("m", "DIRECT"));
        var e2 = journal.appendEvent(runId, RunEventType.RUN_STATE_CHANGED,
                Map.of("fromState", "CREATED", "toState", "RUNNING"));
        var e3 = journal.appendEvent(runId, RunEventType.DECISION_RECORDED, Map.of());

        assertThat(e1.sequence()).isEqualTo(1L);
        assertThat(e2.sequence()).isEqualTo(2L);
        assertThat(e3.sequence()).isEqualTo(3L);
        assertThat(e1.payload()).containsEntry("eventVersion", "run-event-v1");
        assertThat(journal.findEventsAfter(runId, 1L))
                .extracting(RunEvent::sequence).containsExactly(2L, 3L);
    }

    @Test
    void concurrentAppendsNeverDuplicateSequence() throws Exception {

        var runId = insertRun(ExecutionMode.REACT);
        int threads = 6;
        int perThread = 15;
        var barrier = new CountDownLatch(threads);
        var done = new CountDownLatch(threads);
        var failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().start(() -> {
                try {
                    barrier.countDown();
                    barrier.await();
                    for (int j = 0; j < perThread; j++) {
                        journal.appendEvent(runId, RunEventType.DECISION_RECORDED, Map.of());
                    }
                } catch (Exception ex) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).hasValue(0);

        var events = journal.findEventsAfter(runId, 0L);
        assertThat(events).hasSize(threads * perThread);
        assertThat(events).extracting(RunEvent::sequence)
                .doesNotHaveDuplicates()
                .isSorted();
    }

    @Test
    void checkpointAndArtifactEventAreWrittenAtomically() {

        var runId = insertRun(ExecutionMode.REACT);
        var artifact = com.pulseink.agent.artifact.AgentArtifact.create(
                "run-1-CONTENT_DRAFT-1", runId, "unified",
                com.pulseink.agent.artifact.ArtifactType.CONTENT_DRAFT,
                1, Map.of("title", "Hello"), List.of(), Instant.now());
        var checkpoint = RunCheckpoint.of(
                runId, "ARTIFACT", List.of(artifact),
                new BudgetSnapshot(2, 1, 300, 2), 2, 0, Instant.now());

        var event = journal.saveCheckpointAndAppendEvent(
                checkpoint, RunEventType.ARTIFACT_CREATED,
                Map.of("artifactId", artifact.artifactId()));

        assertThat(event.sequence()).isEqualTo(1L);
        var rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM run_checkpoint WHERE run_id = ?", Long.class, runId);
        assertThat(rows).isEqualTo(1L);
        var restored = journal.latestCheckpoint(runId).orElseThrow();
        assertThat(restored.schemaVersion()).isEqualTo(1);
        assertThat(restored.runId()).isEqualTo(runId);
        assertThat(restored.artifacts()).hasSize(1);
        assertThat(restored.artifacts().get(0).content()).containsEntry("title", "Hello");
        assertThat(restored.budgetSnapshot().tokensUsed()).isEqualTo(300);
        assertThat(restored.lastPersistedEventSequence()).isEqualTo(event.sequence());
    }

    @Test
    void latestCheckpointReturnsOnlyTheNewestRow() {

        var runId = insertRun(ExecutionMode.REACT);
        var first = RunCheckpoint.of(runId, "ARTIFACT", List.of(), BudgetSnapshot.ZERO, 1, 0, Instant.now());
        var second = RunCheckpoint.of(runId, "ARTIFACT", List.of(), new BudgetSnapshot(2, 0, 200, 2), 2, 0, Instant.now());

        journal.saveCheckpointAndAppendEvent(first, RunEventType.ARTIFACT_CREATED, Map.of());
        journal.saveCheckpointAndAppendEvent(second, RunEventType.ARTIFACT_CREATED, Map.of());

        var latest = journal.latestCheckpoint(runId).orElseThrow();
        assertThat(latest.budgetSnapshot().tokensUsed()).isEqualTo(200);
        assertThat(latest.lastCompletedRound()).isEqualTo(2);
    }

    @Test
    void modelCallHappensOutsideDatabaseTransaction() {

        var eventService = new RunEventService(journal);
        var runId = insertRun(ExecutionMode.DIRECT);
        var transactionFlags = new java.util.ArrayList<Boolean>();
        AgentModelPort trackingModel = (request, consumer) -> {
            transactionFlags.add(TransactionSynchronizationManager.isActualTransactionActive());
            consumer.accept(new ModelStreamEvent.Started(
                    request.requestId(), "fake", "pulseink-fake"));
            consumer.accept(new ModelStreamEvent.ContentDelta(request.requestId(), """
                    {"decision":"FINAL","decisionSummary":"done",
                     "artifacts":[{"type":"CONTENT_DRAFT","content":{"d":1}}]}
                    """));
            consumer.accept(new ModelStreamEvent.Completed(request.requestId(), "STOP"));
            return () -> {};
        };
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(), trackingModel);
        var router = new ModelRouter(List.of(route));
        var parser = new JacksonAgentDecisionParser();
        var reactLoop = new ReactLoop(router, parser, new ToolRegistry(List.of()));
        var runner = new com.pulseink.agent.orchestration.RoleAgentRunner(
                router, new com.pulseink.client.model.JacksonPlanParser(),
                new com.pulseink.agent.plan.PlanValidator(12), reactLoop);
        var profileFactory = new com.pulseink.agent.orchestration.RoleProfileFactory(
                new com.pulseink.client.profile.YamlRoleProfileCatalog("agent-profiles"));
        var coordinator = new com.pulseink.agent.orchestration.RunCoordinator(
                runner,
                new com.pulseink.client.model.JacksonPlanParser(),
                new com.pulseink.agent.plan.PlanValidator(12),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                3, 12000, profileFactory, 3);
        var service = new RunExecutionService(
                runRepository,
                campaignRepository,
                eventService,
                journal,
                new DirectAgentEngine(router, parser),
                new UnifiedAgentRunner(reactLoop),
                coordinator,
                new ModelPolicy(List.of("fake"), Set.of()),
                Runnable::run);

        AgentExecutionResult result = service.execute(runId);

        assertThat(result).isNotNull();
        assertThat(transactionFlags).containsOnly(false);
        var row = jdbcTemplate.queryForObject(
                "SELECT state FROM campaign_run WHERE id = ?", String.class, runId);
        assertThat(row).isEqualTo("WAITING_APPROVAL");
    }

    private long insertRun(ExecutionMode mode) {
        jdbcTemplate.update("""
                INSERT INTO campaign
                    (name, objective, audience, channels_json, constraints_json, status, created_by, version)
                VALUES (?, ?, ?, ?, ?, 'DRAFT', 1, 0)
                """, "Campaign", "objective", "audience", "[\"BLOG\"]", "[]");
        var campaignId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var run = CampaignRun.create(campaignId, ExecutionPolicy.REACT);
        run.select(new ExecutionDecision(
                mode, "selector-v1", List.of("MANUAL_POLICY_OVERRIDE"), Map.of(), 8_000L));
        var persisted = runRepository.insert(run);
        return persisted.id();
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }
}
