package com.pulseink.agent.react;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.BudgetTracker;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelCompletion;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelStreamEvent;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReactLoopTest {

    private static final String VALIDATE = "builtin.deterministic_validate";
    private static final String FINAL_DRAFT = """
            {"decision":"FINAL","decisionSummary":"draft ready",
             "artifacts":[{"type":"CONTENT_DRAFT","content":{"title":"Hello"},
                           "sourceRefs":["ref-1"]}]}
            """;

    private ToolRegistry registryWith(ToolProvider provider) {
        return new ToolRegistry(List.of(provider));
    }

    private ToolProvider validatingProvider(int[] counter) {
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
                counter[0]++;
                return ToolResult.of("{\"valid\":true,\"issues\":[]}");
            }
        };
    }

    private AgentProfile profile(ExecutionBudget budget, Set<String> tools) {
        return AgentProfile.unified(
                "unified", tools,
                new ModelPolicy(List.of("fake"), Set.of()),
                budget);
    }

    private AgentExecutionRequest reactRequest(AgentProfile profile) {
        return new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.REACT, profile,
                "objective", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);
    }

    private ReactLoop loop(FakeModelAdapter fake, ToolRegistry registry,
                           BudgetTracker.MutableClock clock) {
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(), fake);
        return new ReactLoop(
                new ModelRouter(List.of(route)),
                new JacksonAgentDecisionParser(),
                registry,
                clock);
    }

    @Test
    void configuredCompletionBoundaryReachesTheModelRequest() {
        var capturedRequest = new AtomicReference<ModelRequest>();
        var capturedTimeout = new AtomicReference<Duration>();
        AgentModelPort model = new AgentModelPort() {
            @Override
            public com.pulseink.agent.model.ModelStreamHandle stream(
                    ModelRequest request,
                    java.util.function.Consumer<ModelStreamEvent> consumer) {
                throw new AssertionError("complete should be used");
            }

            @Override
            public ModelCompletion complete(ModelRequest request, Duration timeout) {
                capturedRequest.set(request);
                capturedTimeout.set(timeout);
                return new ModelCompletion(
                        request.requestId(), "fake", "pulseink-fake",
                        FINAL_DRAFT, 10, 20, "STOP");
            }
        };
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(), model);
        var loop = new ReactLoop(
                new ModelRouter(List.of(route)),
                new JacksonAgentDecisionParser(),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()),
                4096,
                Duration.ofSeconds(90));

        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of())), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(capturedRequest.get().maxTokens()).isEqualTo(4096);
        assertThat(capturedTimeout.get()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void toolCallThenFinalProducesArtifactWithObservationEvents() {
        var counter = new int[1];
        var loop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"TOOL_CALL","decisionSummary":"validate",
                         "toolCall":{"qualifiedName":"builtin.deterministic_validate",
                                     "arguments":{"content":"draft"}}}
                        """),
                FakeModelAdapter.Scene.of(FINAL_DRAFT))),
                registryWith(validatingProvider(counter)),
                new BudgetTracker.MutableClock(Instant.now()));

        var events = new ArrayList<AgentRuntimeEvent>();
        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of(VALIDATE))), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(counter[0]).isEqualTo(1);
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).type()).isEqualTo(ArtifactType.CONTENT_DRAFT);
        assertThat(result.metrics().modelCalls()).isEqualTo(2);
        assertThat(result.metrics().toolCalls()).isEqualTo(1);
        assertThat(events).anyMatch(AgentRuntimeEvent.ToolCallStarted.class::isInstance);
        assertThat(events).anyMatch(AgentRuntimeEvent.ToolCallCompleted.class::isInstance);
        assertThat(events).anyMatch(AgentRuntimeEvent.ArtifactCompleted.class::isInstance);
    }

    @Test
    void toolObservationIsIncludedInNextModelRequestAndDecisionsAreRecorded() {
        var requests = new ArrayList<ModelRequest>();
        var callIndex = new AtomicInteger();
        AgentModelPort model = (request, consumer) -> {
            requests.add(request);
            String content = callIndex.getAndIncrement() == 0
                    ? """
                      {"decision":"TOOL_CALL","decisionSummary":"validate",
                       "toolCall":{"qualifiedName":"builtin.deterministic_validate",
                                   "arguments":{"content":"draft"}}}
                      """
                    : FINAL_DRAFT;
            consumer.accept(new ModelStreamEvent.Started(
                    request.requestId(), "fake", "pulseink-fake"));
            consumer.accept(new ModelStreamEvent.ContentDelta(request.requestId(), content));
            consumer.accept(new ModelStreamEvent.Usage(request.requestId(), 10, 20));
            consumer.accept(new ModelStreamEvent.Completed(request.requestId(), "STOP"));
            return () -> {};
        };
        var loop = new ReactLoop(
                new ModelRouter(List.of(new ModelRoute(
                        "fake", "pulseink-fake", Set.of(), model))),
                new JacksonAgentDecisionParser(),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));
        var events = new ArrayList<AgentRuntimeEvent>();

        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of(VALIDATE))), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).systemPrompt())
                .contains("TOOL_CALL", "FINAL", "REPLAN", "NEED_APPROVAL")
                .contains("builtin.deterministic_validate")
                .contains("content:string")
                .contains("Return JSON only");
        assertThat(requests.get(1).userPrompt())
                .contains("builtin.deterministic_validate")
                .contains("{\"valid\":true,\"issues\":[]}");
        assertThat(events).filteredOn(AgentRuntimeEvent.DecisionRecorded.class::isInstance)
                .hasSize(2);
    }

    @Test
    void unauthorizedToolNeverReachesProvider() {
        var counter = new int[1];
        var loop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"TOOL_CALL","decisionSummary":"validate",
                         "toolCall":{"qualifiedName":"builtin.deterministic_validate",
                                     "arguments":{"content":"draft"}}}
                        """))),
                registryWith(validatingProvider(counter)),
                new BudgetTracker.MutableClock(Instant.now()));

        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of())), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.TOOL_FAILURE);
        assertThat(counter[0]).isEqualTo(0);
    }

    @Test
    void reactRoundLimitStopsAtFourRounds() {
        var counter = new int[1];
        var toolCallJson = """
                {"decision":"TOOL_CALL","decisionSummary":"validate",
                 "toolCall":{"qualifiedName":"builtin.deterministic_validate",
                             "arguments":{"content":"draft"}}}
                """;
        var loop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of(toolCallJson),
                FakeModelAdapter.Scene.of(toolCallJson),
                FakeModelAdapter.Scene.of(toolCallJson),
                FakeModelAdapter.Scene.of(toolCallJson))),
                registryWith(validatingProvider(counter)),
                new BudgetTracker.MutableClock(Instant.now()));

        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of(VALIDATE))), event -> {});

        assertThat(result.terminalReason())
                .isEqualTo(AgentTerminalReason.REACT_ROUND_LIMIT_EXCEEDED);
        assertThat(counter[0]).isEqualTo(4);
        assertThat(result.metrics().reactRounds()).isEqualTo(4);
    }

    @Test
    void tokenLimitTerminatesBeforeNextModelCall() {
        var counter = new int[1];
        var budget = new ExecutionBudget(10, 10, 100L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var loop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"TOOL_CALL","decisionSummary":"validate",
                         "toolCall":{"qualifiedName":"builtin.deterministic_validate",
                                     "arguments":{"content":"draft"}}}
                        """, 60, 60),
                FakeModelAdapter.Scene.of(FINAL_DRAFT, 60, 60))),
                registryWith(validatingProvider(counter)),
                new BudgetTracker.MutableClock(Instant.now()));

        var result = loop.execute(reactRequest(profile(budget, Set.of(VALIDATE))),
                event -> {});

        assertThat(result.terminalReason())
                .isEqualTo(AgentTerminalReason.TOKEN_LIMIT_EXCEEDED);
        assertThat(result.metrics().modelCalls()).isEqualTo(1);
    }

    @Test
    void invalidOutputRepairedOnceThenSucceeds() {
        var counter = new int[1];
        var loop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("not valid json at all"),
                FakeModelAdapter.Scene.of(FINAL_DRAFT))),
                registryWith(validatingProvider(counter)),
                new BudgetTracker.MutableClock(Instant.now()));

        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of())), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.metrics().modelCalls()).isEqualTo(2);
    }

    @Test
    void repairedCompletionTokensAreIncludedInMetrics() {
        var loop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("not valid json", 10, 20),
                FakeModelAdapter.Scene.of(FINAL_DRAFT, 30, 40))),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));

        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of())), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.metrics().totalTokens()).isEqualTo(100L);
        assertThat(result.finalBudget().tokensUsed()).isEqualTo(100L);
    }

    @Test
    void invalidOutputTwiceTerminatesDeterministically() {
        var loop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("not valid json"),
                FakeModelAdapter.Scene.of("still not valid json"))),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));

        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of())), event -> {});

        assertThat(result.terminalReason())
                .isEqualTo(AgentTerminalReason.INVALID_MODEL_OUTPUT);
        assertThat(result.metrics().modelCalls()).isEqualTo(2);
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    void roleSemanticOutputViolationGetsOneRepairAttempt() {
        var requests = new ArrayList<ModelRequest>();
        var callIndex = new AtomicInteger();
        AgentModelPort model = (request, consumer) -> {
            requests.add(request);
            String content = callIndex.getAndIncrement() == 0
                    ? """
                      {"decision":"FINAL","decisionSummary":"wrong role output",
                       "artifacts":[{"type":"CONTENT_DRAFT",
                                     "content":{"text":"wrong"},"sourceRefs":[]}]}
                      """
                    : """
                      {"decision":"FINAL","decisionSummary":"strategy repaired",
                       "artifacts":[{"type":"CONTENT_STRATEGY",
                                     "content":{"strategy":"valid"},"sourceRefs":[]}]}
                      """;
            consumer.accept(new ModelStreamEvent.Started(
                    request.requestId(), "fake", "pulseink-fake"));
            consumer.accept(new ModelStreamEvent.ContentDelta(request.requestId(), content));
            consumer.accept(new ModelStreamEvent.Usage(request.requestId(), 10, 20));
            consumer.accept(new ModelStreamEvent.Completed(request.requestId(), "STOP"));
            return () -> {};
        };
        var loop = new ReactLoop(
                new ModelRouter(List.of(new ModelRoute(
                        "fake", "pulseink-fake", Set.of(), model))),
                new JacksonAgentDecisionParser(),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));
        var budget = new ExecutionBudget(5, 0, 10_000L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var roleProfile = AgentProfile.role(
                "strategist-v1",
                com.pulseink.agent.orchestration.AgentRole.STRATEGIST,
                Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                budget,
                "You are PulseInk Strategist.",
                Set.of(ArtifactType.CONTENT_STRATEGY),
                5, 0, 4);

        var result = loop.execute(reactRequest(roleProfile), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.metrics().modelCalls()).isEqualTo(2);
        assertThat(result.artifacts()).extracting(a -> a.type())
                .containsExactly(ArtifactType.CONTENT_STRATEGY);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).userPrompt())
                .contains("exactly one artifact", "CONTENT_STRATEGY")
                .doesNotContain("CONTENT_DRAFT|CONTENT_STRATEGY");
        assertThat(requests.get(1).userPrompt())
                .contains("previous response violated the JSON protocol")
                .contains("exactly one artifact", "CONTENT_STRATEGY");
    }

    @Test
    void retryableFailureFallsBackOnce() {
        var fallback = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of(FINAL_DRAFT)));
        var primary = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.failure("MODEL_TIMEOUT", "provider timed out"),
                FakeModelAdapter.Scene.failure("MODEL_PROVIDER_ERROR", "provider exploded")));
        var primaryRoute = new ModelRoute("fake", "pulseink-fake", Set.of(), primary);
        var fallbackRoute = new ModelRoute("ark", "doubao", Set.of(), fallback);
        var loop = new ReactLoop(
                new ModelRouter(List.of(primaryRoute, fallbackRoute)),
                new JacksonAgentDecisionParser(),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));
        var profile = AgentProfile.unified(
                "unified", Set.of(),
                new ModelPolicy(List.of("fake", "ark"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));

        var result = loop.execute(reactRequest(profile), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.metrics().modelCalls()).isEqualTo(3);
    }

    @Test
    void retryableFailureWithoutFallbackRetriesSameProviderOnce() {
        var primary = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.failure("MODEL_TIMEOUT", "provider timed out"),
                FakeModelAdapter.Scene.of(FINAL_DRAFT)));
        var loop = new ReactLoop(
                new ModelRouter(List.of(new ModelRoute(
                        "fake", "pulseink-fake", Set.of(), primary))),
                new JacksonAgentDecisionParser(),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));

        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of())), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.metrics().modelCalls()).isEqualTo(2);
    }

    @Test
    void retryableFailureWithoutConfiguredFallbackTerminatesDeterministically() {
        var primary = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.failure("MODEL_TIMEOUT", "provider timed out"),
                FakeModelAdapter.Scene.failure("MODEL_PROVIDER_ERROR", "provider exploded")));
        var loop = new ReactLoop(
                new ModelRouter(List.of(new ModelRoute(
                        "fake", "pulseink-fake", Set.of(), primary))),
                new JacksonAgentDecisionParser(),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));

        var result = loop.execute(reactRequest(profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of())), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.MODEL_FAILURE);
        assertThat(result.metrics().modelCalls()).isEqualTo(2);
    }

    @Test
    void nonRetryableFailureDoesNotFallback() {
        var fallback = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of(FINAL_DRAFT)));
        var primary = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.failure("MODEL_EMPTY_RESPONSE", "empty")));
        var loop = new ReactLoop(
                new ModelRouter(List.of(
                        new ModelRoute("fake", "pulseink-fake", Set.of(), primary),
                        new ModelRoute("ark", "doubao", Set.of(), fallback))),
                new JacksonAgentDecisionParser(),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));
        var profile = AgentProfile.unified(
                "unified", Set.of(),
                new ModelPolicy(List.of("fake", "ark"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));

        var result = loop.execute(reactRequest(profile), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.MODEL_FAILURE);
        assertThat(result.metrics().modelCalls()).isEqualTo(1);
    }

    @Test
    void replanAndNeedApprovalProduceTerminalReasonsWithoutArtifacts() {
        var loop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"REPLAN","decisionSummary":"need more context"}
                        """))),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));
        var profile = profile(
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                Set.of());

        var replanResult = loop.execute(reactRequest(profile), event -> {});
        assertThat(replanResult.terminalReason())
                .isEqualTo(AgentTerminalReason.REPLAN_REQUESTED);
        assertThat(replanResult.artifacts()).isEmpty();

        var needApprovalLoop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"NEED_APPROVAL","decisionSummary":"requires approval"}
                        """))),
                registryWith(validatingProvider(new int[1])),
                new BudgetTracker.MutableClock(Instant.now()));
        var approvalResult = needApprovalLoop.execute(reactRequest(profile), event -> {});
        assertThat(approvalResult.terminalReason())
                .isEqualTo(AgentTerminalReason.APPROVAL_REQUIRED);
        assertThat(approvalResult.artifacts()).isEmpty();
    }

    @Test
    void deadlineExceededTerminatesBeforeExternalCall() {
        var now = Instant.now();
        var clock = new BudgetTracker.MutableClock(now);
        var loop = loop(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of(FINAL_DRAFT))),
                registryWith(validatingProvider(new int[1])),
                clock);
        var budget = new ExecutionBudget(5, 5, 100_000L, 4, 1,
                now.plus(Duration.ofSeconds(30)));

        clock.advanceTo(now.plus(Duration.ofSeconds(31)));

        var result = loop.execute(reactRequest(profile(budget, Set.of())), event -> {});
        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.DEADLINE_EXCEEDED);
        assertThat(result.metrics().modelCalls()).isEqualTo(0);
    }
}
