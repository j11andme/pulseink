package com.pulseink.agent.react;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.domain.execution.ExecutionMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DirectAgentEngineTest {

    private static final Instant NOW = Instant.now();

    private DirectAgentEngine engine(FakeModelAdapter fake) {
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(),
                fake);
        return new DirectAgentEngine(
                new ModelRouter(List.of(route)),
                new JacksonAgentDecisionParser());
    }

    private AgentExecutionRequest directRequest(AgentProfile profile) {
        return new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.DIRECT, profile,
                "objective", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);
    }

    private AgentProfile profile(ExecutionBudget budget) {
        return AgentProfile.unified(
                "unified",
                Set.of("builtin.deterministic_validate"),
                new ModelPolicy(List.of("fake"), Set.of()),
                budget);
    }

    @Test
    void directFINALProducesExactlyOneModelCallAndZeroToolCalls() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"draft ready",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"title":"Hello"},
                               "sourceRefs":["ref-1"]}]}
                """)));
        var engine = engine(fake);
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = engine.execute(directRequest(profile(
                ExecutionBudget.defaultDirect(NOW.plus(Duration.ofMinutes(30))))), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).type())
                .isEqualTo(com.pulseink.agent.artifact.ArtifactType.CONTENT_DRAFT);
        assertThat(result.artifacts().get(0).content()).containsEntry("title", "Hello");
        assertThat(result.metrics().modelCalls()).isEqualTo(1);
        assertThat(result.metrics().toolCalls()).isEqualTo(0);
        assertThat(events).anyMatch(AgentRuntimeEvent.ArtifactCompleted.class::isInstance);
    }

    @Test
    void directRejectsToolCallWithoutSecondModelCall() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"TOOL_CALL","decisionSummary":"use tool",
                 "toolCall":{"qualifiedName":"builtin.deterministic_validate","arguments":{}}}
                """)));
        var engine = engine(fake);

        var result = engine.execute(directRequest(profile(
                ExecutionBudget.defaultDirect(NOW.plus(Duration.ofMinutes(30))))), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.INVALID_MODEL_OUTPUT);
        assertThat(result.metrics().modelCalls()).isEqualTo(1);
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    void directRejectsInvalidStructureWithoutSecondModelCall() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of(
                "this is not valid json")));
        var engine = engine(fake);

        var result = engine.execute(directRequest(profile(
                ExecutionBudget.defaultDirect(NOW.plus(Duration.ofMinutes(30))))), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.INVALID_MODEL_OUTPUT);
        assertThat(result.metrics().modelCalls()).isEqualTo(1);
    }

    @Test
    void directModelFailureTerminatesWithoutFallback() {
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.failure("MODEL_PROVIDER_ERROR", "provider exploded")));
        var engine = engine(fake);

        var result = engine.execute(directRequest(profile(
                ExecutionBudget.defaultDirect(NOW.plus(Duration.ofMinutes(30))))), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.MODEL_FAILURE);
        assertThat(result.metrics().modelCalls()).isEqualTo(1);
        assertThat(result.metrics().totalTokens()).isZero();
    }

    @Test
    void directRejectsNonDirectModeBeforeExternalCall() {
        var fake = new FakeModelAdapter(List.of());
        var engine = engine(fake);
        var request = new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.REACT, profile(
                        ExecutionBudget.defaultReact(NOW.plus(Duration.ofMinutes(30)))),
                "objective", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);

        assertThatThrownBy(() -> engine.execute(request, event -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directRejectsPriorArtifactsBeforeExternalCall() {
        var fake = new FakeModelAdapter(List.of());
        var engine = engine(fake);
        var artifact = com.pulseink.agent.artifact.AgentArtifact.create(
                "a1", 1L, "unified", com.pulseink.agent.artifact.ArtifactType.CONTENT_DRAFT,
                1, java.util.Map.of("k", "v"), List.of(), NOW);
        var request = new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.DIRECT, profile(
                        ExecutionBudget.defaultDirect(NOW.plus(Duration.ofMinutes(30)))),
                "objective", List.of(artifact), BudgetSnapshot.ZERO,
                ApprovalState.NOT_REQUIRED);

        assertThatThrownBy(() -> engine.execute(request, event -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directFinalRequiresExactlyOneContentDraft() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"two drafts",
                 "artifacts":[
                   {"type":"CONTENT_DRAFT","content":{"a":1}},
                   {"type":"REVIEW_REPORT","content":{"b":2}}
                 ]}
                """)));
        var engine = engine(fake);

        var result = engine.execute(directRequest(profile(
                ExecutionBudget.defaultDirect(NOW.plus(Duration.ofMinutes(30))))), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.INVALID_MODEL_OUTPUT);
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    void supportedModeIsDirect() {
        assertThat(engine(new FakeModelAdapter()).supportedMode()).isEqualTo(ExecutionMode.DIRECT);
    }
}
