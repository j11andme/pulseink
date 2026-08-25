package com.pulseink.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.api.AgentExecutionObserver;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
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
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.plan.PlanParser;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.agent.plan.PlanTask;
import com.pulseink.agent.plan.PlanTaskAccess;
import com.pulseink.agent.plan.PlanValidator;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.client.model.JacksonPlanParser;
import com.pulseink.client.tool.JavaToolProvider;
import com.pulseink.domain.execution.ExecutionMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RoleAgentRunnerTest {

    private static final String VALID_PLAN_JSON = """
            {"schemaVersion":1,"tasks":[
              {"taskId":"strategy","role":"STRATEGIST","objective":"form strategy","dependsOn":[],
               "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_STRATEGY","access":"READ_ONLY"},
              {"taskId":"create","role":"CREATOR","objective":"write draft","dependsOn":["strategy"],
               "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_DRAFT","access":"READ_ONLY"}]}
            """;

    private RoleAgentRunner runner(FakeModelAdapter fake, ToolRegistry registry) {
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(), fake);
        var router = new ModelRouter(List.of(route));
        var reactLoop = new ReactLoop(router, new JacksonAgentDecisionParser(), registry,
                new BudgetTracker.MutableClock(Instant.now()));
        return new RoleAgentRunner(router, new JacksonPlanParser(), new PlanValidator(12),
                reactLoop);
    }

    private AgentProfile plannerProfile() {
        return AgentProfile.role("planner-v1", AgentRole.PLANNER, Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                "You are PulseInk Planner.",
                Set.of(), 3, 0, 1);
    }

    private AgentProfile researcherProfile() {
        return AgentProfile.role("researcher-v1", AgentRole.RESEARCHER,
                Set.of("builtin.knowledge_search"),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                "You are PulseInk Researcher.",
                Set.of(ArtifactType.EVIDENCE_PACK), 8, 6, 6);
    }

    private AgentExecutionRequest rootRequest() {
        return new AgentExecutionRequest(
                1L, "run-1", ExecutionMode.ORCHESTRATED, plannerProfile(),
                "brief", List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);
    }

    @Test
    void plannerProducesValidPlan() {
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of(VALID_PLAN_JSON)));
        var outcome = runner(fake, new ToolRegistry(List.of()))
                .plan(rootRequest(), plannerProfile(), event -> {});

        assertThat(outcome.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(outcome.plan()).isNotNull();
        assertThat(outcome.plan().tasks()).hasSize(2);
        assertThat(outcome.metrics().modelCalls()).isEqualTo(1);
    }

    @Test
    void plannerUsesConfiguredCompletionBoundary() {
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
                        VALID_PLAN_JSON, 10, 20, "STOP");
            }
        };
        var router = new ModelRouter(List.of(
                new ModelRoute("fake", "pulseink-fake", Set.of(), model)));
        var reactLoop = new ReactLoop(router, new JacksonAgentDecisionParser(),
                new ToolRegistry(List.of()), new BudgetTracker.MutableClock(Instant.now()));
        var runner = new RoleAgentRunner(router, new JacksonPlanParser(),
                new PlanValidator(12), reactLoop, 4096, Duration.ofSeconds(90));

        var outcome = runner.plan(rootRequest(), plannerProfile(), event -> {});

        assertThat(outcome.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(capturedRequest.get().maxTokens()).isEqualTo(4096);
        assertThat(capturedTimeout.get()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void plannerRepairsInvalidJsonOnce() {
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("not valid json"),
                FakeModelAdapter.Scene.of(VALID_PLAN_JSON)));
        var outcome = runner(fake, new ToolRegistry(List.of()))
                .plan(rootRequest(), plannerProfile(), event -> {});

        assertThat(outcome.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(outcome.metrics().modelCalls()).isEqualTo(2);
    }

    @Test
    void plannerFallsBackOnceOnRetryableFailure() {
        var fallback = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of(VALID_PLAN_JSON)));
        var primary = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.failure("MODEL_TIMEOUT", "provider timed out"),
                FakeModelAdapter.Scene.failure("MODEL_PROVIDER_ERROR", "provider exploded")));
        var router = new ModelRouter(List.of(
                new ModelRoute("fake", "m", Set.of(), primary),
                new ModelRoute("ark", "m2", Set.of(), fallback)));
        var reactLoop = new ReactLoop(router, new JacksonAgentDecisionParser(),
                new ToolRegistry(List.of()), new BudgetTracker.MutableClock(Instant.now()));
        var runner = new RoleAgentRunner(router, new JacksonPlanParser(),
                new PlanValidator(12), reactLoop);
        var fallbackProfile = AgentProfile.role("planner-v1", AgentRole.PLANNER, Set.of(),
                new ModelPolicy(List.of("fake", "ark"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                "You are PulseInk Planner.", Set.of(), 3, 0, 1);

        var outcome = runner.plan(rootRequest(), fallbackProfile, event -> {});

        assertThat(outcome.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(outcome.metrics().modelCalls()).isEqualTo(3);
    }

    @Test
    void plannerRetriesSameProviderOnceBeforeRequiringFallback() {
        var primary = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.failure("MODEL_TIMEOUT", "provider timed out"),
                FakeModelAdapter.Scene.of(VALID_PLAN_JSON)));

        var outcome = runner(primary, new ToolRegistry(List.of()))
                .plan(rootRequest(), plannerProfile(), event -> {});

        assertThat(outcome.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(outcome.metrics().modelCalls()).isEqualTo(2);
    }

    @Test
    void plannerValidatorRejectionFails() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"schemaVersion":1,"tasks":[
                  {"taskId":"a","role":"STRATEGIST","objective":"o","dependsOn":["a"],
                   "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_STRATEGY","access":"READ_ONLY"}]}
                """)));
        var outcome = runner(fake, new ToolRegistry(List.of()))
                .plan(rootRequest(), plannerProfile(), event -> {});

        assertThat(outcome.terminalReason()).isEqualTo(AgentTerminalReason.INVALID_MODEL_OUTPUT);
    }

    @Test
    void researcherSeesOnlyKnowledgeSearchTool() {
        var registry = registryWithTools("builtin.knowledge_search", "builtin.deterministic_validate");
        var schemas = registry.schemasFor(researcherProfile());
        assertThat(schemas).extracting(ToolDefinition::qualifiedName)
                .containsExactly("builtin.knowledge_search");
    }

    @Test
    void strategistSeesNoTools() {
        var registry = registryWithTools("builtin.knowledge_search", "builtin.deterministic_validate");
        var profile = AgentProfile.role("strategist-v1", AgentRole.STRATEGIST, Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                "You are PulseInk Strategist.", Set.of(ArtifactType.CONTENT_STRATEGY), 5, 0, 4);
        assertThat(registry.schemasFor(profile)).isEmpty();
    }

    @Test
    void executeTaskProducesExactlyOneArtifactWithTaskId() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"draft",
                 "artifacts":[{"type":"CONTENT_STRATEGY","content":{"k":"v"}}]}
                """)));
        var registry = new ToolRegistry(List.of());
        var task = new PlanTask("strategy", AgentRole.STRATEGIST, "form strategy", List.of(),
                Set.of(), ArtifactType.CONTENT_STRATEGY, PlanTaskAccess.READ_ONLY);
        var profile = AgentProfile.role("strategist-v1", AgentRole.STRATEGIST, Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                "You are PulseInk Strategist.", Set.of(ArtifactType.CONTENT_STRATEGY), 5, 0, 4);

        var result = runner(fake, registry).executeTask(
                new RoleTaskRequest(1L, "run-1-task-strategy", task,
                        "brief", List.of(),
                        ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                        ApprovalState.NOT_REQUIRED),
                profile, event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).taskId()).isEqualTo("strategy");
        assertThat(result.artifacts().get(0).type()).isEqualTo(ArtifactType.CONTENT_STRATEGY);
        assertThat(result.mode()).isEqualTo(ExecutionMode.ORCHESTRATED);
    }

    @Test
    void executeTaskRejectsWrongOutputType() {
        var wrongOutput = FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"wrong",
                 "artifacts":[{"type":"CONTENT_DRAFT","content":{"k":"v"}}]}
                """);
        var fake = new FakeModelAdapter(List.of(wrongOutput, wrongOutput));
        var task = new PlanTask("strategy", AgentRole.STRATEGIST, "form strategy", List.of(),
                Set.of(), ArtifactType.CONTENT_STRATEGY, PlanTaskAccess.READ_ONLY);
        var profile = AgentProfile.role("strategist-v1", AgentRole.STRATEGIST, Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                "You are PulseInk Strategist.", Set.of(ArtifactType.CONTENT_STRATEGY), 5, 0, 4);

        var result = runner(fake, new ToolRegistry(List.of())).executeTask(
                new RoleTaskRequest(1L, "run-1-task-strategy", task,
                        "brief", List.of(),
                        ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                        ApprovalState.NOT_REQUIRED),
                profile, event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.INVALID_MODEL_OUTPUT);
        assertThat(result.artifacts()).isEmpty();
    }

    @Test
    void executeTaskReplanAndNeedApprovalPassThrough() {
        for (String decision : List.of("REPLAN", "NEED_APPROVAL")) {
            var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of(
                    "{\"decision\":\"" + decision
                            + "\",\"decisionSummary\":\"blocked\"}")));
            var task = new PlanTask("strategy", AgentRole.STRATEGIST, "o", List.of(),
                    Set.of(), ArtifactType.CONTENT_STRATEGY, PlanTaskAccess.READ_ONLY);
            var profile = AgentProfile.role("strategist-v1", AgentRole.STRATEGIST, Set.of(),
                    new ModelPolicy(List.of("fake"), Set.of()),
                    ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                    "You are PulseInk Strategist.",
                    Set.of(ArtifactType.CONTENT_STRATEGY), 5, 0, 4);
            var result = runner(fake, new ToolRegistry(List.of())).executeTask(
                    new RoleTaskRequest(1L, "r-1", task, "brief", List.of(),
                            ExecutionBudget.defaultReact(
                                    Instant.now().plus(Duration.ofMinutes(30))),
                            ApprovalState.NOT_REQUIRED),
                    profile, event -> {});
            assertThat(result.terminalReason()).isIn(
                    AgentTerminalReason.REPLAN_REQUESTED,
                    AgentTerminalReason.APPROVAL_REQUIRED);
        }
    }

    @Test
    void executeTaskUsesDependencyArtifactsAsPrior() {
        var fake = new FakeModelAdapter(List.of(FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"strategy",
                 "artifacts":[{"type":"CONTENT_STRATEGY","content":{"s":1}}]}
                """)));
        var prior = com.pulseink.agent.artifact.AgentArtifact.create(
                "a1", 1L, "research-a", ArtifactType.EVIDENCE_PACK, 1,
                Map.of("e", 1), List.of("ref-1"), Instant.now());
        var task = new PlanTask("strategy", AgentRole.STRATEGIST, "o",
                List.of("research-a"), Set.of(ArtifactType.EVIDENCE_PACK),
                ArtifactType.CONTENT_STRATEGY, PlanTaskAccess.READ_ONLY);
        var profile = AgentProfile.role("strategist-v1", AgentRole.STRATEGIST, Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                "You are PulseInk Strategist.",
                Set.of(ArtifactType.CONTENT_STRATEGY), 5, 0, 4);

        var result = runner(fake, new ToolRegistry(List.of())).executeTask(
                new RoleTaskRequest(1L, "r-1", task, "brief", List.of(prior),
                        ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                        ApprovalState.NOT_REQUIRED),
                profile, event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.artifacts()).hasSize(2);
        assertThat(result.artifacts().get(0).taskId()).isEqualTo("research-a");
        assertThat(result.artifacts().get(1).taskId()).isEqualTo("strategy");
    }

    private static ToolRegistry registryWithTools(String... qualifiedNames) {
        var registrations = new java.util.ArrayList<JavaToolProvider.Registration>();
        for (String qn : qualifiedNames) {
            String localName = qn.substring(qn.lastIndexOf('.') + 1);
            registrations.add(new JavaToolProvider.Registration(
                    ToolDefinition.of("builtin", localName, "tool",
                            ToolDefinition.Schema.empty(), ToolRisk.READ),
                    (ToolCall call, Duration timeout) -> ToolResult.of("ok")));
        }
        return new ToolRegistry(List.of(new JavaToolProvider("builtin", registrations)));
    }
}
