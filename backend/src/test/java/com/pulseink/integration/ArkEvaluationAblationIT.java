package com.pulseink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.model.AgentModelPort;
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
import com.pulseink.client.evaluation.FrozenEvaluationToolProvider;
import com.pulseink.client.evaluation.FrozenKnowledgeFixtureLoader;
import com.pulseink.client.evaluation.FrozenSearchFixtureLoader;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.client.model.JacksonPlanParser;
import com.pulseink.client.profile.YamlRoleProfileCatalog;
import com.pulseink.client.tool.DeterministicValidateTool;
import com.pulseink.client.tool.JavaToolProvider;
import com.pulseink.config.ModelProperties;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.service.evaluation.AgentRuntimeEvaluationPolicyExecutor;
import com.pulseink.service.evaluation.EvaluationRequest;
import com.pulseink.service.evaluation.EvaluationCaseCatalog;
import com.pulseink.service.evaluation.EvaluationPolicyExecutor;
import com.pulseink.service.evaluation.EvaluationRuntimeDescriptor;
import com.pulseink.service.evaluation.EvaluationScenarioContext;
import com.pulseink.service.evaluation.EvaluationScorer;
import com.pulseink.service.evaluation.EvaluationSuite;
import com.pulseink.service.evaluation.LlmJudgeScorer;
import com.pulseink.service.evaluation.RunEvaluationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Full 18-case, four-policy ablation against the configured real Volcano Ark model. */
@Tag("real-model")
@EnabledIfSystemProperty(named = "pulseink.real-model-smoke", matches = "true")
class ArkEvaluationAblationIT {

    @Test
    void fullFourPolicyAblationRunsOnRealArkAndWritesAuditableReports()
            throws Exception {
        var application = new SpringApplication(
                ArkOrchestratedSmokeIT.MinimalSmokeConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of(
                "spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration"));
        try (var context = application.run()) {
            String provider = context.getEnvironment()
                    .getProperty("pulseink.model.provider", "");
            if (!"ark".equals(provider)) {
                throw new IllegalStateException(
                        "real ablation requires pulseink.model.provider=ark");
            }
            var properties = context.getBean(ModelProperties.class);
            String modelId = properties.ark().model();
            AgentModelPort primary = context.getBean("primaryModelPort", AgentModelPort.class);
            var mapper = new ObjectMapper().findAndRegisterModules();
            Path evalRoot = Path.of("..", "evals");
            Path reportRoot = evalRoot.resolve("reports");
            var modelPolicy = new ModelPolicy(List.of(provider), Set.of());
            var router = new ModelRouter(List.of(
                    new ModelRoute(provider, modelId, Set.of(), primary)));
            var decisionParser = new JacksonAgentDecisionParser();
            var planParser = new JacksonPlanParser();
            var planValidator = new PlanValidator(12);
            var completionTimeout = Duration.ofSeconds(Long.getLong(
                    "pulseink.real-eval.completion-timeout-seconds", 180L));
            int maxOutputTokens = Integer.getInteger(
                    "pulseink.real-eval.max-output-tokens", 8_192);
            var scenarioContext = new EvaluationScenarioContext();
            var frozenSearch = new FrozenSearchFixtureLoader(evalRoot, mapper);
            var frozenKnowledge = new FrozenKnowledgeFixtureLoader(evalRoot, mapper);
            var registry = new ToolRegistry(List.of(new FrozenEvaluationToolProvider(
                    scenarioContext, frozenSearch, frozenKnowledge, mapper)));
            var reactLoop = new ReactLoop(
                    router, decisionParser, registry,
                    Clock.systemUTC(), maxOutputTokens, completionTimeout, 0.0);
            var direct = new DirectAgentEngine(
                    router, decisionParser, Clock.systemUTC(), maxOutputTokens,
                    completionTimeout, 0.0);
            var react = new UnifiedAgentRunner(reactLoop);
            var roleRunner = new RoleAgentRunner(
                    router, planParser, planValidator, reactLoop,
                    maxOutputTokens, completionTimeout, 0.0);
            var profileFactory = new RoleProfileFactory(
                    new YamlRoleProfileCatalog("agent-profiles"));

            try (var orchestrationExecutor =
                         Executors.newVirtualThreadPerTaskExecutor()) {
                var orchestrated = new RunCoordinator(
                        roleRunner, planParser, planValidator,
                        orchestrationExecutor, 3, 12_000, profileFactory, 3);
                var runtimeExecutor = new AgentRuntimeEvaluationPolicyExecutor(
                        new RuleBasedExecutionModeSelector(),
                        List.of(direct, react, orchestrated),
                        modelPolicy,
                        scenarioContext,
                        Clock.systemUTC(),
                        new EvaluationRuntimeDescriptor(provider, modelId, false));
                EvaluationPolicyExecutor observedExecutor = new EvaluationPolicyExecutor() {
                    @Override
                    public com.pulseink.service.evaluation.EvaluationExecution execute(
                            com.pulseink.service.evaluation.EvaluationCase testCase,
                            ExecutionPolicy policy) {
                        System.out.printf(
                                "REAL_EVAL_START case=%s policy=%s%n",
                                testCase.caseId(), policy);
                        var execution = runtimeExecutor.execute(testCase, policy);
                        System.out.printf(
                                "REAL_EVAL_DONE case=%s policy=%s state=%s latencyMs=%d%n",
                                testCase.caseId(), policy,
                                execution.finalState(), execution.latencyMs());
                        return execution;
                    }

                    @Override
                    public EvaluationRuntimeDescriptor runtimeDescriptor() {
                        return runtimeExecutor.runtimeDescriptor();
                    }
                };
                var sourceCatalog = new FileSystemEvaluationCaseCatalog(evalRoot, mapper);
                var selectedCases = selectedCases(sourceCatalog);
                var selectedPolicies = selectedPolicies();
                EvaluationCaseCatalog selectedCatalog = () -> selectedCases;
                var service = new RunEvaluationService(
                        selectedCatalog,
                        observedExecutor,
                        new EvaluationScorer(),
                        new LlmJudgeScorer(primary, mapper, Duration.ofSeconds(180)),
                        new FileSystemEvaluationReportWriter(reportRoot, mapper));

                var report = service.run(new EvaluationRequest(
                        EvaluationSuite.FULL,
                        selectedPolicies,
                        true));

                int expectedExecutions = selectedCases.stream()
                        .mapToInt(testCase -> (int) selectedPolicies.stream()
                                .filter(testCase.applicablePolicies()::contains).count())
                        .sum();
                assertThat(report.executions()).hasSize(expectedExecutions);
                int expectedComparisons = selectedPolicies.contains(ExecutionPolicy.REACT)
                        && selectedPolicies.contains(ExecutionPolicy.ORCHESTRATED)
                        ? (int) selectedCases.stream().filter(testCase ->
                                testCase.applicablePolicies().contains(ExecutionPolicy.REACT)
                                        && testCase.applicablePolicies().contains(
                                                ExecutionPolicy.ORCHESTRATED)).count()
                        : 0;
                assertThat(report.comparisons()).hasSize(expectedComparisons);
                assertThat(report.summaries()).hasSize(selectedPolicies.size());
                assertThat(report.runtime().provider()).isEqualTo("ark");
                assertThat(report.runtime().model()).isEqualTo(modelId);
                assertThat(report.runtime().simulated()).isFalse();
                if (System.getProperty("pulseink.real-eval.case-ids", "").isBlank()) {
                    assertThat(report.executions()).hasSize(expectedExecutions);
                    assertThat(report.executions())
                            .anyMatch(result -> result.judge().executed());
                } else {
                    assertThat(report.executions())
                            .filteredOn(result -> result.execution().selectedMode()
                                    == com.pulseink.domain.execution.ExecutionMode.ORCHESTRATED)
                            .noneMatch(result -> result.execution().terminalReason()
                                    == com.pulseink.agent.api.AgentTerminalReason.RUNTIME_FAILED);
                    assertThat(report.executions())
                            .allMatch(result -> result.score().status()
                                    != com.pulseink.service.evaluation.EvaluationSampleStatus.ERROR);
                    assertThat(report.executions())
                            .allMatch(result -> !result.execution().trace().isEmpty());
                }
                assertThat(Files.isRegularFile(
                        reportRoot.resolve(report.reportId() + ".json"))).isTrue();
                assertThat(Files.isRegularFile(
                        reportRoot.resolve(report.reportId() + ".md"))).isTrue();
            }
        }
    }

    private static List<com.pulseink.service.evaluation.EvaluationCase> selectedCases(
            FileSystemEvaluationCaseCatalog catalog) {
        String configured = System.getProperty("pulseink.real-eval.case-ids", "").strip();
        if (configured.isEmpty()) {
            return catalog.all();
        }
        Set<String> ids = Arrays.stream(configured.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var selected = catalog.all().stream()
                .filter(testCase -> ids.contains(testCase.caseId()))
                .toList();
        if (selected.size() != ids.size()) {
            throw new IllegalArgumentException("unknown real evaluation case id");
        }
        return selected;
    }

    private static List<ExecutionPolicy> selectedPolicies() {
        String configured = System.getProperty("pulseink.real-eval.policies", "").strip();
        if (configured.isEmpty()) {
            return List.of(ExecutionPolicy.DIRECT, ExecutionPolicy.REACT,
                    ExecutionPolicy.ORCHESTRATED, ExecutionPolicy.ADAPTIVE);
        }
        return Arrays.stream(configured.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> ExecutionPolicy.valueOf(value.toUpperCase(java.util.Locale.ROOT)))
                .distinct()
                .toList();
    }

}
