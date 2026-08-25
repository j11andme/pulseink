package com.pulseink.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.BudgetTracker;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.domain.execution.ExecutionMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRuntimeContractTest {

    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    // ---- AgentEngine default and two-arg execute ----

    @Test
    void defaultExecuteDelegatesToTwoArgWithNoopObserver() {
        var engine = new RecordingEngine();
        var request = defaultRequest();

        engine.execute(request);

        assertThat(engine.callCount).isEqualTo(1);
        assertThat(engine.lastRequest).isSameAs(request);
        assertThat(engine.lastObserver).isNotNull();
        engine.lastObserver.onEvent(new AgentRuntimeEvent.RuntimeFailed(
                1L, NOW, AgentTerminalReason.RUNTIME_FAILED, "test"));
    }

    @Test
    void twoArgExecuteReceivesObserver() {
        var engine = new RecordingEngine();
        var request = defaultRequest();
        var events = new java.util.ArrayList<AgentRuntimeEvent>();

        engine.execute(request, events::add);

        assertThat(engine.callCount).isEqualTo(1);
        assertThat(events).hasSize(1);
    }

    // ---- AgentExecutionRequest defensive copies ----

    @Test
    void requestDefensivelyCopiesPriorArtifacts() {
        var artifact = AgentArtifact.create(
                "a1", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("k", "v"), List.of("r"), NOW);
        var list = new ArrayList<>(List.of(artifact));

        var request = new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.REACT, defaultProfile(),
                "objective", list, BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);

        list.clear();

        assertThat(request.priorArtifacts()).hasSize(1);
        assertThatThrownBy(() -> request.priorArtifacts().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requestRejectsNullMandatoryFields() {
        assertThatThrownBy(() -> new AgentExecutionRequest(
                0L, "req-1", ExecutionMode.REACT, defaultProfile(),
                "obj", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentExecutionRequest(
                1L, null, ExecutionMode.REACT, defaultProfile(),
                "obj", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentExecutionRequest(
                1L, "req-1", null, defaultProfile(),
                "obj", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.REACT, null,
                "obj", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.REACT, defaultProfile(),
                "  ", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- AgentExecutionResult defensive copies ----

    @Test
    void resultDefensivelyCopiesArtifacts() {
        var artifact = AgentArtifact.create(
                "a1", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("k", "v"), List.of("r"), NOW);
        var list = new ArrayList<>(List.of(artifact));
        var metrics = new AgentExecutionResult.Metrics(1, 0, 100, 0);

        var result = new AgentExecutionResult(
                1L, ExecutionMode.DIRECT, list, BudgetSnapshot.ZERO,
                AgentTerminalReason.SUCCEEDED, metrics);

        list.clear();

        assertThat(result.artifacts()).hasSize(1);
        assertThatThrownBy(() -> result.artifacts().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resultRejectsNullMandatoryFields() {
        assertThatThrownBy(() -> new AgentExecutionResult(
                1L, null, List.of(), BudgetSnapshot.ZERO,
                AgentTerminalReason.SUCCEEDED, new AgentExecutionResult.Metrics(0, 0, 0, 0)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AgentExecutionResult(
                1L, ExecutionMode.DIRECT, List.of(), BudgetSnapshot.ZERO,
                null, new AgentExecutionResult.Metrics(0, 0, 0, 0)))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- result must not carry secrets or hidden reasoning ----

    @Test
    void resultDoesNotExposePromptsOrSecrets() {
        var result = new AgentExecutionResult(
                1L, ExecutionMode.DIRECT, List.of(), BudgetSnapshot.ZERO,
                AgentTerminalReason.SUCCEEDED, new AgentExecutionResult.Metrics(1, 0, 100, 0));

        assertThat(result).hasNoNullFieldsOrProperties();
        for (var method : result.getClass().getMethods()) {
            String name = method.getName().toLowerCase();
            assertThat(name).doesNotContain("prompt").doesNotContain("secret")
                    .doesNotContain("token").doesNotContain("apikey")
                    .doesNotContain("password").doesNotContain("credential");
        }
    }

    // ---- AgentTerminalReason is a stable enum ----

    @Test
    void terminalReasonIsStableEnum() {
        assertThat(java.util.EnumSet.allOf(AgentTerminalReason.class)).contains(
                AgentTerminalReason.SUCCEEDED,
                AgentTerminalReason.APPROVAL_REQUIRED,
                AgentTerminalReason.REPLAN_REQUESTED,
                AgentTerminalReason.HUMAN_INTERVENTION_REQUIRED,
                AgentTerminalReason.MODEL_CALL_LIMIT_EXCEEDED,
                AgentTerminalReason.TOOL_CALL_LIMIT_EXCEEDED,
                AgentTerminalReason.TOKEN_LIMIT_EXCEEDED,
                AgentTerminalReason.REACT_ROUND_LIMIT_EXCEEDED,
                AgentTerminalReason.DEADLINE_EXCEEDED,
                AgentTerminalReason.INVALID_MODEL_OUTPUT,
                AgentTerminalReason.MODEL_FAILURE,
                AgentTerminalReason.TOOL_FAILURE,
                AgentTerminalReason.CHECKPOINT_INVALID,
                AgentTerminalReason.RUNTIME_FAILED);
    }

    // ---- AgentRuntimeEvent sealed hierarchy ----

    @Test
    void runtimeEventIncludesRepairLifecycleTypes() {
        assertThat(AgentRuntimeEvent.class.isSealed()).isTrue();
        assertThat(AgentRuntimeEvent.class.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .contains("ReviewIssueCreated", "RepairRoundStarted",
                        "ArtifactInvalidated", "RepairExhausted");
    }

    @Test
    void runtimeFailedEventDoesNotLeakSecrets() {
        var event = new AgentRuntimeEvent.RuntimeFailed(
                1L, NOW, AgentTerminalReason.MODEL_FAILURE, "provider error");
        assertThat(event.message()).doesNotContain("Bearer").doesNotContain("apiKey");
    }

    // ---- helpers ----

    private static AgentProfile defaultProfile() {
        return AgentProfile.of("unified", AgentRole.CREATOR, Set.of("builtin.deterministic_validate"));
    }

    private static AgentExecutionRequest defaultRequest() {
        return new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.REACT, defaultProfile(),
                "objective", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);
    }

    static final class RecordingEngine implements AgentEngine {
        int callCount = 0;
        AgentExecutionRequest lastRequest;
        AgentExecutionObserver lastObserver;

        @Override
        public ExecutionMode supportedMode() {
            return ExecutionMode.REACT;
        }

        @Override
        public AgentExecutionResult execute(
                AgentExecutionRequest request, AgentExecutionObserver observer) {
            callCount++;
            lastRequest = request;
            lastObserver = observer;
            observer.onEvent(new AgentRuntimeEvent.RuntimeFailed(
                    request.runId(), NOW, AgentTerminalReason.RUNTIME_FAILED, "recording"));
            return new AgentExecutionResult(
                    request.runId(), request.mode(), List.of(), BudgetSnapshot.ZERO,
                    AgentTerminalReason.SUCCEEDED,
                    new AgentExecutionResult.Metrics(0, 0, 0, 0));
        }
    }
}
