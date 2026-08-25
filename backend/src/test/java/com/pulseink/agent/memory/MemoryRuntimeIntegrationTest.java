package com.pulseink.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamHandle;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.ArtifactContextRenderer;
import com.pulseink.agent.react.DirectAgentEngine;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.react.UnifiedAgentRunner;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.config.properties.MemoryProperties;
import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignBrief;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.CampaignStatus;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightScopeType;
import com.pulseink.service.campaign.CampaignRepository;
import com.pulseink.service.campaign.QueryCampaignUseCase;
import com.pulseink.service.campaign.RunEvent;
import com.pulseink.service.campaign.RunEventService;
import com.pulseink.service.campaign.RunEventType;
import com.pulseink.service.campaign.RunExecutionService;
import com.pulseink.service.campaign.RunJournal;
import com.pulseink.service.campaign.RunRepository;
import com.pulseink.service.memory.ApprovedInsightHit;
import com.pulseink.service.memory.CampaignEpisodicMemory;
import com.pulseink.service.memory.MemoryPort;
import com.pulseink.service.memory.RunWorkingMemory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Proves that DIRECT, REACT and ORCHESTRATED all actually route their model requests through
 * the ContextAssembler: the fake memory port injects a marker approved insight that must reach
 * every model call in every mode.
 */
class MemoryRuntimeIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00Z");
    private static final String MARKER = "APPROVED-INSIGHT-MARKER";

    @Test
    void directModePassesAssembledContextToTheModel() {
        var recording = new RecordingModelPort(List.of(
                FakeModelAdapter.Scene.of(directDecisionJson())));
        var service = service(recording, ExecutionMode.DIRECT);

        service.execute(2L);

        assertThat(recording.userPrompts).isNotEmpty();
        assertThat(recording.userPrompts.getFirst()).contains(MARKER)
                .contains("[WORKING_MEMORY]");
    }

    @Test
    void reactModePassesAssembledContextToTheModel() {
        var recording = new RecordingModelPort(List.of(
                FakeModelAdapter.Scene.of(reactDecisionJson())));
        var service = service(recording, ExecutionMode.REACT);

        service.execute(2L);

        assertThat(recording.userPrompts).isNotEmpty();
        assertThat(recording.userPrompts.getFirst()).contains(MARKER)
                .contains("[WORKING_MEMORY]");
    }

    @Test
    void orchestratedModeAssemblesPlannerAndRoleContexts() {
        var recording = new RecordingModelPort(List.of(
                FakeModelAdapter.Scene.of(plannerJson()),
                FakeModelAdapter.Scene.of(strategistJson()),
                FakeModelAdapter.Scene.of(creatorJson())));
        var service = service(recording, ExecutionMode.ORCHESTRATED);

        service.execute(2L);

        assertThat(recording.userPrompts).hasSize(3);
        assertThat(recording.userPrompts.get(0)).contains(MARKER)
                .contains("[APPROVED_INSIGHTS]");
        assertThat(recording.userPrompts.get(1)).contains(MARKER);
        assertThat(recording.userPrompts.get(2)).contains(MARKER)
                .contains("[DEPENDENCY_ARTIFACTS]");
    }

    private RunExecutionService service(RecordingModelPort recording, ExecutionMode mode) {
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(), recording);
        var router = new ModelRouter(List.of(route));
        var parser = new JacksonAgentDecisionParser();
        var reactLoop = new ReactLoop(router, parser, new ToolRegistry(List.of()));
        var runner = new com.pulseink.agent.orchestration.RoleAgentRunner(
                router, new com.pulseink.client.model.JacksonPlanParser(),
                new com.pulseink.agent.plan.PlanValidator(12), reactLoop);
        var profileFactory = new com.pulseink.agent.orchestration.RoleProfileFactory(
                new com.pulseink.client.profile.YamlRoleProfileCatalog("agent-profiles"));
        var assembler = new DefaultContextAssembler(
                new FakeMemoryPort(), new ArtifactContextRenderer(12_000));
        var coordinator = new com.pulseink.agent.orchestration.RunCoordinator(
                runner,
                new com.pulseink.client.model.JacksonPlanParser(),
                new com.pulseink.agent.plan.PlanValidator(12),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                3, 12_000, profileFactory, 3,
                new com.pulseink.agent.repair.StrictReviewArtifactInterpreter(),
                new com.pulseink.agent.repair.RepairRouter(),
                new com.pulseink.agent.repair.ArtifactInvalidator(),
                2,
                assembler);
        return new RunExecutionService(
                new FakeRunRepository(mode),
                new FakeCampaignRepository(),
                new RunEventService(new FakeRunJournal()),
                new FakeRunJournal(),
                new DirectAgentEngine(router, parser),
                new UnifiedAgentRunner(reactLoop),
                coordinator,
                (runId, result) -> {},
                new ModelPolicy(List.of("fake"), Set.of()),
                Runnable::run,
                assembler,
                new MemoryProperties(null, null, null, 12_000, null, null, null, null));
    }

    private static String directDecisionJson() {
        return """
                {"decision":"FINAL","decisionSummary":"draft ready",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"text":"PulseInk"}}]}
                """;
    }

    private static String reactDecisionJson() {
        return """
                {"decision":"FINAL","decisionSummary":"draft ready",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"text":"PulseInk"}}]}
                """;
    }

    private static String plannerJson() {
        return """
                {"schemaVersion":1,"tasks":[
                  {"taskId":"strategy-main","role":"STRATEGIST","objective":"form strategy",
                   "dependsOn":[],"requiredArtifactTypes":[],
                   "outputArtifactType":"CONTENT_STRATEGY","access":"READ_ONLY"},
                  {"taskId":"create-main","role":"CREATOR","objective":"write draft",
                   "dependsOn":["strategy-main"],"requiredArtifactTypes":[],
                   "outputArtifactType":"CONTENT_DRAFT","access":"READ_ONLY"}]}
                """;
    }

    private static String strategistJson() {
        return """
                {"decision":"FINAL","decisionSummary":"strategy",
                 "artifacts":[{"type":"CONTENT_STRATEGY","content":{"strategy":"unified"}}]}
                """;
    }

    private static String creatorJson() {
        return """
                {"decision":"FINAL","decisionSummary":"draft",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"draft":"PulseInk"}}]}
                """;
    }

    private static final class RecordingModelPort implements AgentModelPort {

        private final FakeModelAdapter delegate;
        final List<String> userPrompts = new CopyOnWriteArrayList<>();

        RecordingModelPort(List<FakeModelAdapter.Scene> scenes) {
            this.delegate = new FakeModelAdapter(scenes);
        }

        @Override
        public ModelStreamHandle stream(ModelRequest request,
                                        Consumer<ModelStreamEvent> events) {
            userPrompts.add(request.userPrompt());
            return delegate.stream(request, events);
        }
    }

    private static final class FakeMemoryPort implements MemoryPort {

        @Override
        public WorkingMemoryResult loadRunWorkingMemory(long runId) {
            return new WorkingMemoryResult(
                    new RunWorkingMemory(runId, "ARTIFACT", 1, 0, 1L, NOW,
                            List.of(new RunWorkingMemory.ArtifactSummary(
                                    "artifact-1", "create-main",
                                    com.pulseink.agent.artifact.ArtifactType.CONTENT_DRAFT,
                                    1, com.pulseink.agent.artifact.ArtifactStatus.VALID,
                                    "working-memory-marker")),
                            com.pulseink.agent.budget.BudgetSnapshot.ZERO),
                    false);
        }

        @Override
        public CampaignEpisodicMemory loadCampaignEpisode(long runId) {
            return new CampaignEpisodicMemory(1L, runId, List.of(), List.of(), List.of());
        }

        @Override
        public List<ApprovedInsightHit> searchApprovedInsights(String query,
                                                               CampaignChannel channel,
                                                               int topK) {
            return List.of(new ApprovedInsightHit(9L, 1L, MARKER, "正文",
                    InsightCategory.CHANNEL_PATTERN, InsightScopeType.WORKSPACE, "",
                    List.of(CampaignChannel.BLOG), 0.8, NOW));
        }
    }

    private static final class FakeRunRepository implements RunRepository {

        private final ExecutionMode mode;
        final Map<Long, CampaignRun> rows = new LinkedHashMap<>();

        FakeRunRepository(ExecutionMode mode) {
            this.mode = mode;
        }

        @Override
        public CampaignRun insert(CampaignRun run) {
            rows.put(run.id(), run);
            return run;
        }

        @Override
        public Optional<CampaignRun> findById(long runId) {
            if (!rows.containsKey(runId)) {
                rows.put(runId, CampaignRun.materialize(
                        runId, 1L, ExecutionPolicy.DIRECT, RunState.CREATED, mode,
                        "selector-v1", List.of(), Map.of(), 8_000L, null, 0L,
                        null, null, NOW, NOW));
            }
            return Optional.of(rows.get(runId));
        }

        @Override
        public void update(CampaignRun run) {
            rows.put(run.id(), run);
        }

        @Override
        public List<CampaignRun> findByCampaignId(long campaignId) {
            return rows.values().stream()
                    .filter(run -> run.campaignId() == campaignId)
                    .toList();
        }
    }

    private static final class FakeCampaignRepository implements CampaignRepository {

        @Override
        public Campaign insert(Campaign draft) {
            return draft;
        }

        @Override
        public QueryCampaignUseCase.CampaignPage findPage(int page, int size) {
            return new QueryCampaignUseCase.CampaignPage(List.of(), page, size, 0, 0);
        }

        @Override
        public Optional<Campaign> findById(long campaignId) {
            return Optional.of(new Campaign(
                    1L, "c",
                    new CampaignBrief("objective", "audience",
                            List.of(CampaignChannel.BLOG), List.of()),
                    CampaignStatus.DRAFT, 1L, 0L, Optional.of(NOW), Optional.of(NOW)));
        }
    }

    private static final class FakeRunJournal implements RunJournal {

        final List<RunEvent> events = new ArrayList<>();
        private final List<com.pulseink.agent.checkpoint.RunCheckpoint> checkpoints =
                new ArrayList<>();

        @Override
        public RunEvent appendEvent(long runId, RunEventType type,
                                    Map<String, Object> payload) {
            var event = new RunEvent(runId, events.size() + 1L, type,
                    Map.copyOf(payload), NOW);
            events.add(event);
            return event;
        }

        @Override
        public RunEvent saveCheckpointAndAppendEvent(
                com.pulseink.agent.checkpoint.RunCheckpoint checkpoint,
                RunEventType type, Map<String, Object> payload) {
            checkpoints.add(checkpoint);
            return appendEvent(checkpoint.runId(), type, payload);
        }

        @Override
        public Optional<com.pulseink.agent.checkpoint.RunCheckpoint> latestCheckpoint(
                long runId) {
            return checkpoints.isEmpty() ? Optional.empty()
                    : Optional.of(checkpoints.get(checkpoints.size() - 1));
        }

        @Override
        public List<RunEvent> findEventsAfter(long runId, long lastSequence) {
            return List.of();
        }
    }
}
