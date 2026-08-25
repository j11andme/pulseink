package com.pulseink.domain.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.execution.ExecutionDecision;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CampaignRunTest {

    @Test
    void runCannotPublishBeforeApproval() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);

        assertThatThrownBy(run::beginPublishing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run must be approved before publishing");
    }

    @Test
    void selectedModeIsRecordedWhenRunStarts() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);
        var decision = reactDecision();

        run.start(decision);

        assertThat(run.selectedMode()).isEqualTo(ExecutionMode.REACT);
        assertThat(run.selectorPolicyVersion()).isEqualTo("selector-v1");
        assertThat(run.selectionReasonCodes()).containsExactly("SEQUENTIAL_TASK");
        assertThat(run.selectionFeatureSnapshot())
                .containsEntry("sequentialDependency", 0.9);
        assertThat(run.estimatedTokenBudget()).isEqualTo(8_000L);
        assertThat(run.state()).isEqualTo(RunState.RUNNING);
    }

    @Test
    void approvedRunCanProgressToCompletion() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);
        run.start(reactDecision());

        run.requestApproval();
        assertThat(run.state()).isEqualTo(RunState.WAITING_APPROVAL);

        run.beginPublishing();
        assertThat(run.state()).isEqualTo(RunState.PUBLISHING);

        run.complete(java.time.Instant.now());
        assertThat(run.state()).isEqualTo(RunState.COMPLETED);
    }

    @Test
    void terminalRunRejectsFurtherTransitions() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);
        run.start(reactDecision());
        run.fail("model provider unavailable");

        assertThat(run.state()).isEqualTo(RunState.FAILED);
        assertThat(run.failureReason()).isEqualTo("model provider unavailable");
        assertThatThrownBy(run::requestApproval)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cannot request approval while run is FAILED");
    }

    @Test
    void selectRecordsDecisionAndKeepsRunInCreatedState() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);

        run.select(reactDecision());

        assertThat(run.state()).isEqualTo(RunState.CREATED);
        assertThat(run.selectedMode()).isEqualTo(ExecutionMode.REACT);
        assertThat(run.selectorPolicyVersion()).isEqualTo("selector-v1");
        assertThat(run.selectionReasonCodes()).containsExactly("SEQUENTIAL_TASK");
        assertThat(run.selectionFeatureSnapshot())
                .containsEntry("sequentialDependency", 0.9);
        assertThat(run.estimatedTokenBudget()).isEqualTo(8_000L);
    }

    @Test
    void selectRecordsTheDecisionOnlyOnce() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);
        run.select(reactDecision());

        assertThatThrownBy(() -> run.select(reactDecision()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("execution decision has already been recorded");
    }

    @Test
    void selectRejectsNullDecision() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);

        assertThatThrownBy(() -> run.select(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void failureReasonMustContainUsefulInformation() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);

        assertThatThrownBy(() -> run.fail("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failure reason must not be blank");
    }

    @Test
    void beginExecutionMovesSelectedCreatedRunToRunning() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);
        run.select(reactDecision());
        var startedAt = java.time.Instant.now();

        run.beginExecution(startedAt);

        assertThat(run.state()).isEqualTo(RunState.RUNNING);
        assertThat(run.startedAt()).isEqualTo(startedAt);
    }

    @Test
    void beginExecutionRejectsUnselectedAndNonCreatedRuns() {
        var unselected = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);
        assertThatThrownBy(() -> unselected.beginExecution(java.time.Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("run has no selected execution mode");

        var running = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);
        running.start(reactDecision());
        assertThatThrownBy(() -> running.beginExecution(java.time.Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cannot begin execution while run is RUNNING");
    }

    @Test
    void waitForHumanMovesRunningRunToWaitingHuman() {
        var run = CampaignRun.create(1L, ExecutionPolicy.ADAPTIVE);
        run.start(reactDecision());

        run.waitForHuman();

        assertThat(run.state()).isEqualTo(RunState.WAITING_HUMAN);
        assertThatThrownBy(run::requestApproval)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cannot request approval while run is WAITING_HUMAN");
    }

    @Test
    void materializedVersionAndTimestampsArePreserved() {
        var createdAt = java.time.Instant.parse("2026-08-04T12:00:00Z");
        var startedAt = java.time.Instant.parse("2026-08-04T12:05:00Z");
        var run = CampaignRun.materialize(
                1L, 1L, ExecutionPolicy.REACT, RunState.RUNNING, ExecutionMode.REACT,
                "selector-v1", List.of(), Map.of(), 8_000L, null, 7L,
                startedAt, null, createdAt, createdAt);

        assertThat(run.version()).isEqualTo(7L);
        assertThat(run.startedAt()).isEqualTo(startedAt);
        assertThat(run.completedAt()).isNull();
    }

    private static ExecutionDecision reactDecision() {
        return new ExecutionDecision(
                ExecutionMode.REACT,
                "selector-v1",
                List.of("SEQUENTIAL_TASK"),
                Map.of("sequentialDependency", 0.9),
                8_000L);
    }
}
