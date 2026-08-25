package com.pulseink.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.orchestration.RoleAgentRunner;
import com.pulseink.agent.orchestration.RoleProfileFactory;
import com.pulseink.agent.orchestration.RunCoordinator;
import com.pulseink.agent.plan.PlanValidator;
import com.pulseink.agent.react.DirectAgentEngine;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.react.UnifiedAgentRunner;
import com.pulseink.agent.selection.RuleBasedExecutionModeSelector;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.client.evaluation.FileSystemEvaluationCaseCatalog;
import com.pulseink.client.evaluation.FileSystemEvaluationReportWriter;
import com.pulseink.client.evaluation.FrozenSearchFixtureLoader;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.client.model.JacksonPlanParser;
import com.pulseink.client.profile.YamlRoleProfileCatalog;
import com.pulseink.client.tool.DeterministicValidateTool;
import com.pulseink.client.tool.JavaToolProvider;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class FakeEvaluationSmokeIT {

    @Test
    void sixCasesAcrossFourPoliciesRunOfflineAndWriteBothReports() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        Path evalRoot = Path.of("..", "evals");
        Path reportRoot = Path.of("target", "evaluation-reports");
        Files.createDirectories(reportRoot);
        var model = FakeModelAdapter.fast();
        var policy = new ModelPolicy(List.of("fake"), Set.of());
        var router = new ModelRouter(List.of(
                new ModelRoute("fake", "pulseink-fake", Set.of(), model)));
        var decisionParser = new JacksonAgentDecisionParser();
        var planParser = new JacksonPlanParser();
        var planValidator = new PlanValidator(12);
        var registry = frozenRegistry();
        var reactLoop = new ReactLoop(router, decisionParser, registry,
                Clock.systemUTC(), 8192, Duration.ofSeconds(10));
        var direct = new DirectAgentEngine(router, decisionParser);
        var react = new UnifiedAgentRunner(reactLoop);
        var roleRunner = new RoleAgentRunner(
                router, planParser, planValidator, reactLoop, 8192, Duration.ofSeconds(10));
        var profileFactory = new RoleProfileFactory(
                new YamlRoleProfileCatalog("agent-profiles"));

        try (var orchestrationExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            var orchestrated = new RunCoordinator(
                    roleRunner, planParser, planValidator, orchestrationExecutor,
                    3, 12_000, profileFactory, 3);
            var executor = new AgentRuntimeEvaluationPolicyExecutor(
                    new RuleBasedExecutionModeSelector(),
                    List.of(direct, react, orchestrated), policy,
                    new EvaluationScenarioContext(), Clock.systemUTC());
            var service = new RunEvaluationService(
                    new FileSystemEvaluationCaseCatalog(evalRoot, mapper), executor,
                    new EvaluationScorer(),
                    new LlmJudgeScorer(model, mapper, Duration.ofSeconds(10)),
                    new FileSystemEvaluationReportWriter(reportRoot, mapper));

            var report = service.run(new EvaluationRequest(
                    EvaluationSuite.SMOKE,
                    List.of(ExecutionPolicy.DIRECT, ExecutionPolicy.REACT,
                            ExecutionPolicy.ORCHESTRATED, ExecutionPolicy.ADAPTIVE), true));

            assertThat(report.executions()).hasSize(22);
            assertThat(report.summaries()).hasSize(4);
            assertThat(report.executions()).allSatisfy(result ->
                    assertThat(result.execution().terminalReason()).isNotNull());
            assertThat(report.executions()).filteredOn(result ->
                    result.policy() == ExecutionPolicy.ADAPTIVE)
                    .extracting(EvaluationRunResult::selectedMode)
                    .containsExactlyInAnyOrder(
                            com.pulseink.domain.execution.ExecutionMode.DIRECT,
                            com.pulseink.domain.execution.ExecutionMode.REACT,
                            com.pulseink.domain.execution.ExecutionMode.ORCHESTRATED,
                            com.pulseink.domain.execution.ExecutionMode.ORCHESTRATED,
                            com.pulseink.domain.execution.ExecutionMode.ORCHESTRATED,
                            com.pulseink.domain.execution.ExecutionMode.ORCHESTRATED);
            assertThat(Files.list(reportRoot).filter(path ->
                    path.getFileName().toString().startsWith(report.reportId())).toList())
                    .hasSize(2);
            String markdown = Files.readString(reportRoot.resolve(report.reportId() + ".md"));
            assertThat(markdown)
                    .contains("REACT vs ORCHESTRATED ablation")
                    .contains("Coordination overhead")
                    .contains("Failure stage/code");
        }
    }

    private static ToolRegistry frozenRegistry() {
        var validate = new DeterministicValidateTool();
        var validateSchema = ToolDefinition.Schema.of(
                Map.of("content", ToolDefinition.PropertySpec.of("string")),
                Set.of("content"), false);
        var searchSchema = ToolDefinition.Schema.of(
                Map.of("query", ToolDefinition.PropertySpec.of("string"),
                        "topK", ToolDefinition.PropertySpec.of("integer")),
                Set.of("query"), false);
        var provider = new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "deterministic_validate", "validate",
                                validateSchema, ToolRisk.READ), validate::validate),
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "knowledge_search", "frozen search",
                                searchSchema, ToolRisk.READ),
                        (call, timeout) -> ToolResult.of("""
                                {"fixtureVersion":"campaign-a-search-v1",
                                 "chunkIds":["product-overview-001","brand-tone-002"]}
                                """))));
        return new ToolRegistry(List.of(provider));
    }
}
