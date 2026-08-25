package com.pulseink.service.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.checkpoint.RunCheckpoint;
import com.pulseink.agent.selection.RuleBasedExecutionModeSelector;
import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignBrief;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.CampaignStatus;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.execution.TaskProperties;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignNotFoundException;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignPage;
import com.pulseink.service.campaign.QueryRunUseCase.RunNotFoundException;
import com.pulseink.service.campaign.StartRunUseCase.StartRunCommand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunApplicationServiceTest {

    private final FakeCampaignRepository campaigns = new FakeCampaignRepository();
    private final FakeRunRepository runs = new FakeRunRepository();
    private final FakeRunJournal journal = new FakeRunJournal();
    private final RunApplicationService service =
            new RunApplicationService(
                    campaigns, runs, new RuleBasedExecutionModeSelector(), journal);

    @Test
    void adaptiveStartPersistsDecisionAndLeavesRunInCreatedState() {
        campaigns.items.add(persistedCampaign(1L));

        var run = service.start(command(1L, ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.8, 2, 2, 2, 0.4, 0.8, 3, 20_000)));

        assertThat(run.id()).isEqualTo(1L);
        assertThat(run.campaignId()).isEqualTo(1L);
        assertThat(run.state()).isEqualTo(RunState.CREATED);
        assertThat(run.selectedMode()).isEqualTo(ExecutionMode.ORCHESTRATED);
        assertThat(run.selectorPolicyVersion()).isEqualTo("selector-v1");
        assertThat(run.selectionReasonCodes()).containsExactly("DECOMPOSABLE_OR_HIGH_RISK");
        assertThat(run.selectionFeatureSnapshot()).containsEntry("channelCount", 2);
        assertThat(run.estimatedTokenBudget()).isEqualTo(20_000L);

        var captured = runs.capturedRun;
        assertThat(captured.id()).isZero();
        assertThat(captured.state()).isEqualTo(RunState.CREATED);
        assertThat(captured.selectedMode()).isEqualTo(ExecutionMode.ORCHESTRATED);
        assertThat(captured.selectionReasonCodes()).isNotEmpty();
    }

    @Test
    void manualFixedPolicyMapsDirectlyAndRecordsManualOverride() {
        campaigns.items.add(persistedCampaign(1L));

        var run = service.start(command(1L, ExecutionPolicy.DIRECT,
                new TaskProperties(0.8, 2, 2, 2, 0.4, 0.8, 3, 20_000)));

        assertThat(run.selectedMode()).isEqualTo(ExecutionMode.DIRECT);
        assertThat(run.selectionReasonCodes()).containsExactly("MANUAL_POLICY_OVERRIDE");
    }

    @Test
    void channelCountMismatchIsRejectedBeforeRepositoryAccess() {
        campaigns.items.add(persistedCampaign(1L));

        assertThatThrownBy(() -> service.start(command(1L, ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.8, 3, 2, 2, 0.4, 0.8, 3, 20_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("task channel count must match the campaign brief channel count");
        assertThat(runs.invocations).isZero();
    }

    @Test
    void missingCampaignThrowsCampaignNotFound() {
        assertThatThrownBy(() -> service.start(command(99L, ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.8, 2, 2, 2, 0.4, 0.8, 3, 20_000))))
                .isInstanceOf(CampaignNotFoundException.class)
                .hasMessage("campaign 99 was not found");
    }

    @Test
    void invalidInputIsRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.start(command(0L, ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.8, 2, 2, 2, 0.4, 0.8, 3, 20_000))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign id must be positive");
        assertThatThrownBy(() -> service.start(command(1L, null,
                new TaskProperties(0.8, 2, 2, 2, 0.4, 0.8, 3, 20_000))))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.start(command(1L, ExecutionPolicy.ADAPTIVE, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.start(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(runs.invocations).isZero();
    }

    @Test
    void executionDecisionReturnsThePersistedRun() {
        var run = persistedRun(1L, 1L, Instant.parse("2026-08-04T12:00:00Z"));
        runs.items.add(run);

        assertThat(service.executionDecision(1L)).isSameAs(run);
    }

    @Test
    void missingRunThrowsRunNotFound() {
        assertThatThrownBy(() -> service.executionDecision(99L))
                .isInstanceOf(RunNotFoundException.class)
                .hasMessage("run 99 was not found");
    }

    @Test
    void nonPositiveRunIdIsRejected() {
        assertThatThrownBy(() -> service.executionDecision(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("run id must be positive");
    }

    @Test
    void historyReturnsRepositoryRunsForTheCampaign() {
        campaigns.items.add(persistedCampaign(1L));
        var run = persistedRun(10L, 1L, Instant.parse("2026-08-04T12:00:00Z"));
        runs.campaignRuns.put(1L, List.of(run));

        assertThat(service.history(1L)).containsExactly(run);
    }

    @Test
    void historyMissingCampaignThrowsCampaignNotFound() {
        assertThatThrownBy(() -> service.history(99L))
                .isInstanceOf(CampaignNotFoundException.class)
                .hasMessage("campaign 99 was not found");
        assertThat(runs.findByCampaignIdInvocations).isZero();
    }

    @Test
    void historyInvalidCampaignIdIsRejected() {
        assertThatThrownBy(() -> service.history(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("campaign id must be positive");
    }

    @Test
    void traceOnEmptyRunReturnsNullCheckpointAndZeroSequence() {
        var run = persistedRun(1L, 1L, Instant.parse("2026-08-04T12:00:00Z"));
        runs.items.add(run);

        var trace = service.trace(1L);

        assertThat(trace.run()).isSameAs(run);
        assertThat(trace.lastEventSequence()).isZero();
        assertThat(trace.checkpoint()).isNull();
        assertThat(trace.events()).isEmpty();
    }

    @Test
    void traceReturnsLatestCheckpointAndOrderedEvents() {
        var run = persistedRun(1L, 1L, Instant.parse("2026-08-04T12:00:00Z"));
        runs.items.add(run);
        var artifact = AgentArtifact.create(
                "run-1-plan-v1", 1L, "planner", ArtifactType.PLAN, 1,
                Map.of("plan", "{\"schemaVersion\":1,\"tasks\":[]}"), List.of(),
                Instant.parse("2026-08-04T12:01:00Z"));
        var checkpoint = RunCheckpoint.of(
                1L, "ARTIFACT", List.of(artifact),
                new BudgetSnapshot(2, 3, 4000L, 1), 1, 1L,
                Instant.parse("2026-08-04T12:01:00Z"));
        journal.checkpoint = Optional.of(checkpoint);
        journal.events = List.of(
                event(1L, 1L, RunEventType.EXECUTION_MODE_SELECTED),
                event(1L, 2L, RunEventType.RUN_STATE_CHANGED));

        var trace = service.trace(1L);

        assertThat(trace.lastEventSequence()).isEqualTo(2L);
        assertThat(trace.checkpoint()).isSameAs(checkpoint);
        assertThat(trace.events()).extracting(RunEvent::sequence).containsExactly(1L, 2L);
    }

    @Test
    void traceMissingRunThrowsRunNotFound() {
        assertThatThrownBy(() -> service.trace(99L))
                .isInstanceOf(RunNotFoundException.class)
                .hasMessage("run 99 was not found");
    }

    private static RunEvent event(long runId, long sequence, RunEventType type) {
        return new RunEvent(runId, sequence, type, Map.of("eventVersion", "run-event-v1"),
                Instant.parse("2026-08-04T12:00:0" + sequence + "Z"));
    }

    private static StartRunCommand command(
            long campaignId, ExecutionPolicy policy, TaskProperties properties) {
        return new StartRunCommand(campaignId, policy, properties);
    }

    private static Campaign persistedCampaign(long id) {
        var brief = new CampaignBrief(
                "向 Java 后端开发者介绍 PulseInk",
                "关注 Agent 工程化的 Java 开发者",
                List.of(CampaignChannel.BLOG, CampaignChannel.SOCIAL),
                List.of("事实性结论必须给出引用"));
        var now = Instant.parse("2026-08-04T12:00:00Z");
        return new Campaign(id, "PulseInk 秋招发布", brief, CampaignStatus.DRAFT, 1L, 0L,
                Optional.of(now), Optional.of(now));
    }

    private static CampaignRun persistedRun(long id, long campaignId, Instant createdAt) {
        return CampaignRun.materialize(
                id,
                campaignId,
                ExecutionPolicy.REACT,
                RunState.CREATED,
                ExecutionMode.REACT,
                "selector-v1",
                List.of("MANUAL_POLICY_OVERRIDE"),
                Map.of("channelCount", 1),
                8_000L,
                null,
                0L,
                null,
                null,
                createdAt,
                createdAt);
    }

    private static final class FakeCampaignRepository implements CampaignRepository {

        private final List<Campaign> items = new ArrayList<>();

        @Override
        public Campaign insert(Campaign draft) {
            throw new UnsupportedOperationException("not used by run tests");
        }

        @Override
        public CampaignPage findPage(int page, int size) {
            throw new UnsupportedOperationException("not used by run tests");
        }

        @Override
        public Optional<Campaign> findById(long campaignId) {
            return items.stream()
                    .filter(campaign -> campaign.id() == campaignId)
                    .findFirst();
        }
    }

    private static final class FakeRunRepository implements RunRepository {

        private final List<CampaignRun> items = new ArrayList<>();
        private final Map<Long, List<CampaignRun>> campaignRuns = new java.util.HashMap<>();
        private CampaignRun capturedRun;
        private int invocations;
        private int findByCampaignIdInvocations;

        @Override
        public CampaignRun insert(CampaignRun run) {
            invocations++;
            capturedRun = run;
            var persisted = CampaignRun.materialize(
                    1L,
                    run.campaignId(),
                    run.requestedPolicy(),
                    run.state(),
                    run.selectedMode(),
                    run.selectorPolicyVersion(),
                    run.selectionReasonCodes(),
                    run.selectionFeatureSnapshot(),
                    run.estimatedTokenBudget(),
                    run.failureReason(),
                    0L,
                    null,
                    null,
                    Instant.parse("2026-08-04T12:00:00Z"),
                    Instant.parse("2026-08-04T12:00:00Z"));
            items.add(persisted);
            return persisted;
        }

        @Override
        public Optional<CampaignRun> findById(long runId) {
            invocations++;
            return items.stream()
                    .filter(run -> run.id() == runId)
                    .findFirst();
        }

        @Override
        public void update(CampaignRun run) {
            throw new UnsupportedOperationException("not used by run tests");
        }

        @Override
        public List<CampaignRun> findByCampaignId(long campaignId) {
            findByCampaignIdInvocations++;
            return List.copyOf(campaignRuns.getOrDefault(campaignId, List.of()));
        }
    }

    private static final class FakeRunJournal implements RunJournal {

        private Optional<RunCheckpoint> checkpoint = Optional.empty();
        private List<RunEvent> events = List.of();

        @Override
        public RunEvent appendEvent(long runId, RunEventType type,
                                    Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used by run application tests");
        }

        @Override
        public RunEvent saveCheckpointAndAppendEvent(
                RunCheckpoint checkpoint, RunEventType type,
                Map<String, Object> payload) {
            throw new UnsupportedOperationException("not used by run application tests");
        }

        @Override
        public Optional<RunCheckpoint> latestCheckpoint(long runId) {
            return checkpoint;
        }

        @Override
        public List<RunEvent> findEventsAfter(long runId, long lastSequence) {
            return events.stream().filter(event -> event.sequence() > lastSequence).toList();
        }
    }
}
