package com.pulseink.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.checkpoint.RunCheckpoint;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.react.DirectAgentEngine;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.react.UnifiedAgentRunner;
import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolProvider;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignBrief;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.CampaignStatus;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.service.content.CaptureRunContentUseCase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunExecutionServiceTest {

    private final FakeRunRepository runs = new FakeRunRepository();
    private final FakeCampaignRepository campaigns = new FakeCampaignRepository();
    private final FakeRunJournal journal = new FakeRunJournal();
    private final RunEventService eventService = new RunEventService(journal);
    private final ModelPolicy policy = new ModelPolicy(List.of("fake"), Set.of());
    private final ToolRegistry tools = new ToolRegistry(List.of(validatingProvider()));
    private final List<AgentExecutionResult> capturedResults = new ArrayList<>();

    private RunExecutionService service(FakeModelAdapter fake) {
        return service(fake, (runId, result) -> capturedResults.add(result));
    }

    private RunExecutionService service(FakeModelAdapter fake,
                                        CaptureRunContentUseCase capture) {
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(), fake);
        var router = new ModelRouter(List.of(route));
        var parser = new JacksonAgentDecisionParser();
        var reactLoop = new ReactLoop(router, parser, tools);
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
        return new RunExecutionService(
                runs,
                campaigns,
                eventService,
                journal,
                new DirectAgentEngine(router, parser),
                new UnifiedAgentRunner(reactLoop),
                coordinator,
                capture,
                policy,
                Runnable::run);
    }

    @Test
    void directRunExecutesModelOnceAndEndsInWaitingApproval() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"draft ready",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"title":"Hello"}}]}
                """)));
        var service = service(fake);
        var run = runs.persist(createdRun(ExecutionMode.DIRECT));

        var result = service.execute(run.id());

        assertThat(result.terminalReason()).isEqualTo(
                com.pulseink.agent.api.AgentTerminalReason.SUCCEEDED);
        var reloaded = runs.items.get(0);
        assertThat(reloaded.state()).isEqualTo(RunState.WAITING_APPROVAL);
        assertThat(capturedResults).containsExactly(result);
        assertThat(journal.events).extracting(RunEvent::type)
                .contains(
                        RunEventType.EXECUTION_MODE_SELECTED,
                        RunEventType.RUN_STATE_CHANGED,
                        RunEventType.ARTIFACT_CREATED);
        assertThat(journal.checkpoints).hasSize(1);
        assertThat(journal.checkpointEventTypes)
                .containsExactly(RunEventType.ARTIFACT_CREATED);
        assertThat(journal.events).filteredOn(
                        event -> event.type() == RunEventType.RUN_STATE_CHANGED)
                .hasSize(2);
        assertThat(journal.events).allSatisfy(event ->
                assertThat(event.payload()).containsEntry("eventVersion", "run-event-v1"));
    }

    @Test
    void captureFailureFailsRunWithStableRuntimeCode() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"draft ready",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"title":"Hello"}}]}
                """)));
        var service = service(fake, (runId, result) -> {
            throw new IllegalStateException("database unavailable");
        });
        var run = runs.persist(createdRun(ExecutionMode.DIRECT));

        service.execute(run.id());

        assertThat(runs.items.get(0).state()).isEqualTo(RunState.FAILED);
        assertThat(runs.items.get(0).failureReason())
                .isEqualTo(com.pulseink.agent.api.AgentTerminalReason.RUNTIME_FAILED.name());
    }

    @Test
    void reactRunRecordsToolCallEventsAndCheckpoint() {
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"TOOL_CALL","decisionSummary":"validate",
                         "toolCall":{"qualifiedName":"builtin.deterministic_validate",
                                     "arguments":{"content":"draft"}}}
                        """),
                FakeModelAdapter.Scene.of("""
                        {"decision":"FINAL","decisionSummary":"done",
                         "artifacts":[{"type":"CONTENT_DRAFT","content":{"d":1}}]}
                        """)));
        var service = service(fake);
        var run = runs.persist(createdRun(ExecutionMode.REACT));

        var result = service.execute(run.id());

        assertThat(result.terminalReason()).isEqualTo(
                com.pulseink.agent.api.AgentTerminalReason.SUCCEEDED);
        assertThat(journal.events).extracting(RunEvent::type)
                .contains(
                        RunEventType.TOOL_CALL_STARTED,
                        RunEventType.TOOL_CALL_COMPLETED,
                        RunEventType.ARTIFACT_CREATED);
        assertThat(journal.checkpoints).hasSize(1);
        assertThat(journal.checkpointEventTypes)
                .containsExactly(RunEventType.ARTIFACT_CREATED);
    }

    @Test
    void orchestratedRunExecutesToWaitingApproval() {
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"schemaVersion":1,"tasks":[
                          {"taskId":"strategy","role":"STRATEGIST","objective":"s",
                           "dependsOn":[],"requiredArtifactTypes":[],
                           "outputArtifactType":"CONTENT_STRATEGY","access":"READ_ONLY"},
                          {"taskId":"create","role":"CREATOR","objective":"c",
                           "dependsOn":["strategy"],"requiredArtifactTypes":[],
                           "outputArtifactType":"CONTENT_DRAFT","access":"READ_ONLY"}]}
                        """),
                FakeModelAdapter.Scene.of("""
                        {"decision":"FINAL","decisionSummary":"strategy",
                         "artifacts":[{"type":"CONTENT_STRATEGY","content":{"s":1},
                                       "sourceRefs":[]}]}
                        """),
                FakeModelAdapter.Scene.of("""
                        {"decision":"FINAL","decisionSummary":"draft",
                         "artifacts":[{"type":"CONTENT_DRAFT","content":{"d":1},
                                       "sourceRefs":[]}]}
                        """)));
        var service = service(fake);
        var run = runs.persist(createdRun(ExecutionMode.ORCHESTRATED));

        var result = service.execute(run.id());

        assertThat(result).isNotNull();
        assertThat(result.terminalReason())
                .isEqualTo(com.pulseink.agent.api.AgentTerminalReason.SUCCEEDED);
        assertThat(runs.items.get(0).state()).isEqualTo(RunState.WAITING_APPROVAL);
        assertThat(journal.events).isNotEmpty();
        assertThat(journal.events).extracting(RunEvent::type)
                .contains(RunEventType.PLAN_VALIDATED,
                        RunEventType.TASK_STARTED, RunEventType.TASK_COMPLETED);
        assertThat(journal.checkpoints).hasSize(3);
    }

    @Test
    void replanTerminalReasonMapsToWaitingHuman() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"REPLAN","decisionSummary":"need more context"}
                """)));
        var service = service(fake);
        var run = runs.persist(createdRun(ExecutionMode.REACT));

        service.execute(run.id());

        assertThat(runs.items.get(0).state()).isEqualTo(RunState.WAITING_HUMAN);
        assertThat(journal.events).anyMatch(event ->
                event.type() == RunEventType.RUN_STATE_CHANGED
                        && "WAITING_HUMAN".equals(event.payload().get("toState")));
    }

    @Test
    void needApprovalTerminalReasonMapsToWaitingApproval() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"NEED_APPROVAL","decisionSummary":"requires approval"}
                """)));
        var service = service(fake);
        var run = runs.persist(createdRun(ExecutionMode.REACT));

        service.execute(run.id());

        assertThat(runs.items.get(0).state()).isEqualTo(RunState.WAITING_APPROVAL);
        assertThat(journal.events).anyMatch(event ->
                event.type() == RunEventType.APPROVAL_REQUIRED);
    }

    @Test
    void invalidOutputMapsToFailedWithStableCode() {
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("not json"),
                FakeModelAdapter.Scene.of("still not json")));
        var service = service(fake);
        var run = runs.persist(createdRun(ExecutionMode.REACT));

        service.execute(run.id());

        assertThat(runs.items.get(0).state()).isEqualTo(RunState.FAILED);
        assertThat(runs.items.get(0).failureReason())
                .isEqualTo(com.pulseink.agent.api.AgentTerminalReason.INVALID_MODEL_OUTPUT.name());
    }

    @Test
    void secondLaunchDoesNotProduceSecondModelCall() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"done",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"d":1}}]}
                """)));
        var service = service(fake);
        var run = runs.persist(createdRun(ExecutionMode.DIRECT));

        service.execute(run.id());
        var second = service.execute(run.id());

        assertThat(second).isNull();
        assertThat(runs.items.get(0).state()).isEqualTo(RunState.WAITING_APPROVAL);
    }

    @Test
    void restoredCheckpointSkipsPriorArtifactAndInheritsBudget() {
        var prior = com.pulseink.agent.artifact.AgentArtifact.create(
                "run-1-CONTENT_DRAFT-1", 1L, "unified",
                com.pulseink.agent.artifact.ArtifactType.CONTENT_DRAFT,
                1, Map.of("title", "Old"), List.of(), Instant.now());
        var checkpoint = RunCheckpoint.of(
                1L, "ARTIFACT", List.of(prior),
                new BudgetSnapshot(1, 0, 100, 1), 1, 0, Instant.now());
        var runningRun = CampaignRun.materialize(
                1L, 1L, ExecutionPolicy.REACT, RunState.RUNNING, ExecutionMode.REACT,
                "selector-v1", List.of(), Map.of(), 8_000L, null, 0L,
                Instant.now(), null, Instant.now(), Instant.now());
        runs.items.add(runningRun);
        journal.checkpoints.add(checkpoint);
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"new draft",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"title":"New"}}]}
                """)));
        var service = service(fake);

        var result = service.execute(1L);

        assertThat(result.artifacts()).hasSize(2);
        assertThat(result.artifacts().get(1).artifactVersion()).isEqualTo(2);
        assertThat(result.finalBudget().tokensUsed()).isGreaterThanOrEqualTo(100);
        var artifactEvents = journal.events.stream()
                .filter(event -> event.type() == RunEventType.ARTIFACT_CREATED)
                .toList();
        assertThat(artifactEvents).hasSize(1);
        assertThat(artifactEvents.get(0).payload()).containsEntry("artifactId", "run-1-CONTENT_DRAFT-2");
    }

    @Test
    void directRunRecoversCompletedArtifactWithoutCallingModelAgain() {
        var prior = com.pulseink.agent.artifact.AgentArtifact.create(
                "run-1-CONTENT_DRAFT-1", 1L, "unified",
                com.pulseink.agent.artifact.ArtifactType.CONTENT_DRAFT,
                1, Map.of("title", "Recovered"), List.of(), Instant.now());
        var checkpoint = RunCheckpoint.of(
                1L, "ARTIFACT", List.of(prior),
                new BudgetSnapshot(1, 0, 30, 0), 0, 3L, Instant.now());
        var runningRun = CampaignRun.materialize(
                1L, 1L, ExecutionPolicy.DIRECT, RunState.RUNNING, ExecutionMode.DIRECT,
                "selector-v1", List.of(), Map.of(), 8_000L, null, 0L,
                Instant.now(), null, Instant.now(), Instant.now());
        runs.items.add(runningRun);
        journal.checkpoints.add(checkpoint);
        var service = service(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.failure(
                        "MODEL_PROVIDER_ERROR", "must not be called"))));

        var result = service.execute(1L);

        assertThat(result.terminalReason())
                .isEqualTo(com.pulseink.agent.api.AgentTerminalReason.SUCCEEDED);
        assertThat(result.artifacts()).containsExactly(prior);
        assertThat(result.metrics().modelCalls()).isEqualTo(1);
        assertThat(runs.items.get(0).state()).isEqualTo(RunState.WAITING_APPROVAL);
        assertThat(journal.events).noneMatch(
                event -> event.type() == RunEventType.ARTIFACT_CREATED);
        assertThat(journal.checkpoints).hasSize(1);
    }

    @Test
    void corruptedCheckpointFailsWithoutModelCall() {
        var runningRun = CampaignRun.materialize(
                1L, 1L, ExecutionPolicy.REACT, RunState.RUNNING, ExecutionMode.REACT,
                "selector-v1", List.of(), Map.of(), 8_000L, null, 0L,
                Instant.now(), null, Instant.now(), Instant.now());
        runs.items.add(runningRun);
        journal.corruptLatest = true;
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"should not run",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"d":1}}]}
                """)));
        var service = service(fake);

        service.execute(1L);

        assertThat(runs.items.get(0).state()).isEqualTo(RunState.FAILED);
        assertThat(runs.items.get(0).failureReason()).isEqualTo("CHECKPOINT_INVALID");
        assertThat(journal.events).noneMatch(event ->
                event.type() == RunEventType.DECISION_RECORDED
                        || event.type() == RunEventType.ARTIFACT_CREATED
                        || event.type() == RunEventType.TOOL_CALL_STARTED);
    }

    private static CampaignRun createdRun(ExecutionMode mode) {
        var run = CampaignRun.create(1L, ExecutionPolicy.REACT);
        run.select(new com.pulseink.domain.execution.ExecutionDecision(
                mode, "selector-v1", List.of("MANUAL_POLICY_OVERRIDE"),
                Map.of(), 8_000L));
        return run;
    }

    private static ToolProvider validatingProvider() {
        var schema = ToolDefinition.Schema.of(
                Map.of("content", ToolDefinition.PropertySpec.of("string")),
                Set.of("content"), false);
        return new ToolProvider() {
            @Override
            public String namespace() {
                return "builtin";
            }

            @Override
            public List<ToolDefinition> discover() {
                return List.of(ToolDefinition.of(
                        "builtin", "deterministic_validate", "validate",
                        schema, ToolRisk.READ));
            }

            @Override
            public ToolResult invoke(ToolCall call, Duration timeout) {
                return ToolResult.of("{\"valid\":true,\"issues\":[]}");
            }
        };
    }

    private static final class FakeRunRepository implements RunRepository {
        private final List<CampaignRun> items = new ArrayList<>();

        CampaignRun persist(CampaignRun run) {
            var persisted = CampaignRun.materialize(
                    1L, run.campaignId(), run.requestedPolicy(), run.state(),
                    run.selectedMode(), run.selectorPolicyVersion(),
                    run.selectionReasonCodes(), run.selectionFeatureSnapshot(),
                    run.estimatedTokenBudget(), run.failureReason(), 0L,
                    run.startedAt(), run.completedAt(), Instant.now(), Instant.now());
            items.add(persisted);
            return persisted;
        }

        @Override
        public CampaignRun insert(CampaignRun run) {
            throw new UnsupportedOperationException("use persist");
        }

        @Override
        public Optional<CampaignRun> findById(long runId) {
            return items.stream().filter(run -> run.id() == runId).findFirst();
        }

        @Override
        public List<CampaignRun> findByCampaignId(long campaignId) {
            return items.stream().filter(run -> run.campaignId() == campaignId).toList();
        }

        @Override
        public void update(CampaignRun run) {
            for (int i = 0; i < items.size(); i++) {
                var existing = items.get(i);
                if (existing.id() == run.id()) {
                    var updated = CampaignRun.materialize(
                            existing.id(), existing.campaignId(), existing.requestedPolicy(),
                            run.state(), existing.selectedMode(), existing.selectorPolicyVersion(),
                            existing.selectionReasonCodes(), existing.selectionFeatureSnapshot(),
                            existing.estimatedTokenBudget(), run.failureReason(),
                            existing.version() + 1, run.startedAt(), run.completedAt(),
                            existing.createdAt(), Instant.now());
                    items.set(i, updated);
                    return;
                }
            }
            throw new IllegalStateException("stale run update for id " + run.id());
        }
    }

    private static final class FakeCampaignRepository implements CampaignRepository {
        @Override
        public Campaign insert(Campaign draft) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.pulseink.service.campaign.QueryCampaignUseCase.CampaignPage findPage(
                int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Campaign> findById(long campaignId) {
            var brief = new CampaignBrief(
                    "objective", "audience",
                    List.of(CampaignChannel.BLOG), List.of());
            return Optional.of(new Campaign(
                    campaignId, "campaign", brief, CampaignStatus.DRAFT, 1L, 0L,
                    Optional.of(Instant.now()), Optional.of(Instant.now())));
        }
    }

    static final class FakeRunJournal implements RunJournal {
        final List<RunEvent> events = new ArrayList<>();
        final List<RunCheckpoint> checkpoints = new ArrayList<>();
        final List<RunEventType> checkpointEventTypes = new ArrayList<>();
        boolean corruptLatest;
        long sequence;

        @Override
        public RunEvent appendEvent(long runId, RunEventType type,
                                    Map<String, Object> payload) {
            var enriched = new java.util.LinkedHashMap<String, Object>();
            enriched.put("eventVersion", RunEvent.EVENT_VERSION);
            enriched.putAll(payload);
            var event = new RunEvent(runId, ++sequence, type, enriched, Instant.now());
            events.add(event);
            return event;
        }

        @Override
        public RunEvent saveCheckpointAndAppendEvent(
                RunCheckpoint checkpoint, RunEventType type,
                Map<String, Object> payload) {
            checkpoints.add(checkpoint);
            checkpointEventTypes.add(type);
            return appendEvent(checkpoint.runId(), type, payload);
        }

        @Override
        public Optional<RunCheckpoint> latestCheckpoint(long runId) {
            if (corruptLatest) {
                throw new IllegalStateException("checkpoint deserialization failed");
            }
            if (checkpoints.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(checkpoints.get(checkpoints.size() - 1));
        }

        @Override
        public List<RunEvent> findEventsAfter(long runId, long lastSequence) {
            return events.stream()
                    .filter(event -> event.runId() == runId
                            && event.sequence() > lastSequence)
                    .toList();
        }
    }
}
