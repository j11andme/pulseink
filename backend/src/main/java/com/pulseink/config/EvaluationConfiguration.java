package com.pulseink.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.orchestration.RoleAgentRunner;
import com.pulseink.agent.orchestration.RoleProfileFactory;
import com.pulseink.agent.orchestration.RunCoordinator;
import com.pulseink.agent.plan.PlanValidator;
import com.pulseink.agent.react.DirectAgentEngine;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.react.UnifiedAgentRunner;
import com.pulseink.agent.selection.ExecutionModeSelector;
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
import com.pulseink.config.properties.EvaluationProperties;
import com.pulseink.service.evaluation.AgentRuntimeEvaluationPolicyExecutor;
import com.pulseink.service.evaluation.EvaluationCaseCatalog;
import com.pulseink.service.evaluation.EvaluationJudge;
import com.pulseink.service.evaluation.EvaluationPolicyExecutor;
import com.pulseink.service.evaluation.EvaluationReportWriter;
import com.pulseink.service.evaluation.EvaluationRuntimeDescriptor;
import com.pulseink.service.evaluation.EvaluationScenarioContext;
import com.pulseink.service.evaluation.EvaluationScorer;
import com.pulseink.service.evaluation.LlmJudgeScorer;
import com.pulseink.service.evaluation.RunEvaluationService;
import com.pulseink.service.evaluation.RunEvaluationUseCase;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvaluationProperties.class)
public class EvaluationConfiguration {

    @Bean
    EvaluationCaseCatalog evaluationCaseCatalog(EvaluationProperties properties,
                                                ObjectMapper objectMapper) {
        return new FileSystemEvaluationCaseCatalog(properties.root(), objectMapper);
    }

    @Bean
    FrozenSearchFixtureLoader frozenSearchFixtureLoader(EvaluationProperties properties,
                                                        ObjectMapper objectMapper) {
        return new FrozenSearchFixtureLoader(properties.root(), objectMapper);
    }

    @Bean
    FrozenKnowledgeFixtureLoader frozenKnowledgeFixtureLoader(
            EvaluationProperties properties, ObjectMapper objectMapper) {
        return new FrozenKnowledgeFixtureLoader(properties.root(), objectMapper);
    }

    @Bean
    EvaluationScenarioContext evaluationScenarioContext() {
        return new EvaluationScenarioContext();
    }

    @Bean
    EvaluationScorer evaluationScorer() {
        return new EvaluationScorer();
    }

    @Bean(destroyMethod = "close")
    ExecutorService evaluationOrchestrationExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * A dedicated evaluation runtime. It reuses the configured model route, while keeping Agent
     * engines, tools and data stores isolated from the production runtime.
     */
    @Bean
    EvaluationPolicyExecutor evaluationPolicyExecutor(
            ExecutionModeSelector selector,
            ExecutorService evaluationOrchestrationExecutor,
            FrozenSearchFixtureLoader fixtures,
            FrozenKnowledgeFixtureLoader knowledge,
            EvaluationScenarioContext scenarioContext,
            ObjectMapper objectMapper,
            ModelRouter modelRouter,
            @Qualifier("runtimeModelPolicy") ModelPolicy modelPolicy,
            ModelProperties modelProperties,
            EvaluationProperties evaluationProperties) {
        var decisionParser = new JacksonAgentDecisionParser();
        var planParser = new JacksonPlanParser();
        var planValidator = new PlanValidator(12);
        var reactLoop = new ReactLoop(
                modelRouter, decisionParser,
                offlineToolRegistry(scenarioContext, fixtures, knowledge, objectMapper),
                Clock.systemUTC(), evaluationProperties.maxOutputTokensPerCall(),
                evaluationProperties.completionTimeout(), 0.0);
        var direct = new DirectAgentEngine(
                modelRouter, decisionParser, Clock.systemUTC(),
                evaluationProperties.maxOutputTokensPerCall(),
                evaluationProperties.completionTimeout(), 0.0);
        var react = new UnifiedAgentRunner(reactLoop);
        var roleRunner = new RoleAgentRunner(
                modelRouter, planParser, planValidator, reactLoop,
                evaluationProperties.maxOutputTokensPerCall(),
                evaluationProperties.completionTimeout(), 0.0);
        var profileFactory = new RoleProfileFactory(
                new YamlRoleProfileCatalog("agent-profiles"));
        var orchestrated = new RunCoordinator(
                roleRunner, planParser, planValidator, evaluationOrchestrationExecutor,
                3, 12_000, profileFactory, 3);
        return new AgentRuntimeEvaluationPolicyExecutor(
                selector, List.of(direct, react, orchestrated),
                modelPolicy, scenarioContext, Clock.systemUTC(),
                runtimeDescriptor(modelProperties));
    }

    @Bean
    EvaluationJudge evaluationJudge(
            @Qualifier("primaryModelPort") AgentModelPort model,
            ObjectMapper objectMapper,
            EvaluationProperties properties) {
        return new LlmJudgeScorer(model, objectMapper, properties.judgeTimeout());
    }

    @Bean
    EvaluationReportWriter evaluationReportWriter(EvaluationProperties properties,
                                                  ObjectMapper objectMapper) {
        return new FileSystemEvaluationReportWriter(properties.reportRoot(), objectMapper);
    }

    @Bean
    RunEvaluationUseCase runEvaluationUseCase(
            EvaluationCaseCatalog catalog,
            EvaluationPolicyExecutor executor,
            EvaluationScorer scorer,
            EvaluationJudge judge,
            EvaluationReportWriter reportWriter) {
        return new RunEvaluationService(catalog, executor, scorer, judge, reportWriter);
    }

    private static ToolRegistry offlineToolRegistry(
            EvaluationScenarioContext scenarioContext,
            FrozenSearchFixtureLoader fixtures,
            FrozenKnowledgeFixtureLoader knowledge,
            ObjectMapper objectMapper) {
        return new ToolRegistry(List.of(new FrozenEvaluationToolProvider(
                scenarioContext, fixtures, knowledge, objectMapper)));
    }

    private static EvaluationRuntimeDescriptor runtimeDescriptor(
            ModelProperties properties) {
        String provider = properties.provider();
        String model = switch (provider) {
            case "ark" -> providerModel(properties.ark(), provider);
            case "zhipu" -> providerModel(properties.zhipu(), provider);
            case "fake" -> "pulseink-fake";
            default -> throw new IllegalStateException(
                    "unsupported evaluation model provider: " + provider);
        };
        return new EvaluationRuntimeDescriptor(provider, model, "fake".equals(provider));
    }

    private static String providerModel(ModelProperties.Provider provider, String name) {
        if (provider == null || provider.model() == null || provider.model().isBlank()) {
            throw new IllegalStateException(
                    "evaluation model must be configured for provider " + name);
        }
        return provider.model();
    }
}
