package com.pulseink.agent.react;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.BudgetTracker;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolProvider;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.domain.execution.ExecutionMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UnifiedAgentRunnerTest {

    private ToolProvider validatingProvider() {
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

    private UnifiedAgentRunner runner(FakeModelAdapter fake, ToolRegistry registry) {
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(), fake);
        return new UnifiedAgentRunner(new ReactLoop(
                new ModelRouter(List.of(route)),
                new JacksonAgentDecisionParser(),
                registry,
                new BudgetTracker.MutableClock(Instant.now())));
    }

    private AgentProfile unifiedProfile() {
        return AgentProfile.unified(
                "unified",
                Set.of("builtin.deterministic_validate"),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));
    }

    private AgentExecutionRequest request(AgentProfile profile, List<AgentArtifact> prior) {
        return new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.REACT, profile,
                "objective", prior, BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);
    }

    @Test
    void scriptedFakeProducesValidateToolCallThenFourBusinessArtifactTypes() {
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"TOOL_CALL","decisionSummary":"validate draft",
                         "toolCall":{"qualifiedName":"builtin.deterministic_validate",
                                     "arguments":{"content":"draft"}}}
                        """),
                FakeModelAdapter.Scene.of("""
                        {"decision":"FINAL","decisionSummary":"all artifacts",
                         "artifacts":[
                           {"type":"EVIDENCE_PACK","content":{"e":1},"sourceRefs":["r1"]},
                           {"type":"CONTENT_STRATEGY","content":{"s":1}},
                           {"type":"CONTENT_DRAFT","content":{"d":1}},
                           {"type":"REVIEW_REPORT","content":{"v":1}}
                         ]}
                        """)));
        var runner = runner(fake, new ToolRegistry(List.of(validatingProvider())));
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = runner.execute(request(unifiedProfile(), List.of()), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.artifacts()).hasSize(4);
        assertThat(result.artifacts()).extracting(AgentArtifact::type)
                .containsExactlyInAnyOrder(
                        ArtifactType.EVIDENCE_PACK, ArtifactType.CONTENT_STRATEGY,
                        ArtifactType.CONTENT_DRAFT, ArtifactType.REVIEW_REPORT);
        assertThat(result.artifacts()).allSatisfy(a -> {
            assertThat(a.schemaVersion()).isEqualTo("artifact-v1");
            assertThat(a.taskId()).isEqualTo("unified");
            assertThat(a.artifactVersion()).isEqualTo(1);
            assertThat(a.status()).isEqualTo(ArtifactStatus.VALID);
        });
        assertThat(result.metrics().modelCalls()).isEqualTo(2);
        assertThat(result.metrics().toolCalls()).isEqualTo(1);
        assertThat(events.stream()
                        .filter(AgentRuntimeEvent.ArtifactCompleted.class::isInstance)
                        .count())
                .isEqualTo(4);
    }

    @Test
    void priorArtifactRestoredIsNotReEmittedAndVersionContinues() {
        var prior = AgentArtifact.create(
                "art-1", 1L, "unified", ArtifactType.CONTENT_DRAFT,
                1, Map.of("title", "Old"), List.of("r1"), Instant.now());
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"FINAL","decisionSummary":"new draft",
                         "artifacts":[{"type":"CONTENT_DRAFT","content":{"title":"New"}}]}
                        """)));
        var runner = runner(fake, new ToolRegistry(List.of(validatingProvider())));
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = runner.execute(request(unifiedProfile(), List.of(prior)), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.artifacts()).hasSize(2);
        assertThat(result.artifacts().get(1).artifactVersion()).isEqualTo(2);
        var completed = events.stream()
                .filter(AgentRuntimeEvent.ArtifactCompleted.class::isInstance)
                .toList();
        assertThat(completed).hasSize(1);
        assertThat(((AgentRuntimeEvent.ArtifactCompleted) completed.get(0)).artifact())
                .isNotEqualTo(prior);
    }

    @Test
    void metricsEqualActualCounts() {
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"TOOL_CALL","decisionSummary":"validate",
                         "toolCall":{"qualifiedName":"builtin.deterministic_validate",
                                     "arguments":{"content":"draft"}}}
                        """, 50, 30),
                FakeModelAdapter.Scene.of("""
                        {"decision":"FINAL","decisionSummary":"done",
                         "artifacts":[{"type":"CONTENT_DRAFT","content":{"d":1}}]}
                        """, 40, 20)));
        var runner = runner(fake, new ToolRegistry(List.of(validatingProvider())));

        var result = runner.execute(request(unifiedProfile(), List.of()), event -> {});

        assertThat(result.metrics().modelCalls()).isEqualTo(2);
        assertThat(result.metrics().toolCalls()).isEqualTo(1);
        assertThat(result.metrics().totalTokens()).isEqualTo(140);
        assertThat(result.metrics().reactRounds()).isEqualTo(2);
    }

    @Test
    void supportedModeIsReact() {
        var runner = runner(new FakeModelAdapter(List.of()), new ToolRegistry(List.of()));
        assertThat(runner.supportedMode()).isEqualTo(ExecutionMode.REACT);
    }
}
