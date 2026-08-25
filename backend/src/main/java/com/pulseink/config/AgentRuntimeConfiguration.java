package com.pulseink.config;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.react.AgentDecisionParser;
import com.pulseink.agent.react.DirectAgentEngine;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.react.UnifiedAgentRunner;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.client.tool.DeterministicValidateTool;
import com.pulseink.client.tool.JavaToolProvider;
import com.pulseink.client.tool.KnowledgeSearchTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the governed agent runtime: built-in deterministic validator, the shared ToolRegistry,
 * the strict decision parser, the primary/fallback ModelRouter and the DIRECT/REACT engines.
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(
        {com.pulseink.config.properties.OrchestrationProperties.class,
                com.pulseink.config.properties.AgentRuntimeProperties.class})
public class AgentRuntimeConfiguration {

    @Bean
    DeterministicValidateTool deterministicValidateTool() {
        return new DeterministicValidateTool();
    }

    @Bean
    JavaToolProvider builtinToolProvider(DeterministicValidateTool tool,
                                         KnowledgeSearchTool knowledgeSearchTool) {
        var validateSchema = ToolDefinition.Schema.of(
                Map.of("content", ToolDefinition.PropertySpec.of("string")),
                Set.of("content"), false);
        var searchSchema = ToolDefinition.Schema.of(
                Map.of(
                        "query", ToolDefinition.PropertySpec.of("string"),
                        "topK", ToolDefinition.PropertySpec.of("integer"),
                        "knowledgeTypes", ToolDefinition.PropertySpec.of("array"),
                        "authorities", ToolDefinition.PropertySpec.of("array"),
                        "updatedAfter", ToolDefinition.PropertySpec.of("string")),
                Set.of("query"), false);
        return new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of(
                                "builtin", "deterministic_validate",
                                "Deterministic structural validation of content",
                                validateSchema, ToolRisk.READ),
                        tool::validate),
                new JavaToolProvider.Registration(
                        ToolDefinition.of(
                                "builtin", "knowledge_search",
                                "Hybrid search over ingested knowledge with evidence summaries",
                                searchSchema, ToolRisk.READ),
                        knowledgeSearchTool::validate)));
    }

    @Bean
    ToolRegistry toolRegistry(JavaToolProvider builtinToolProvider) {
        return new ToolRegistry(List.of(builtinToolProvider));
    }

    @Bean
    AgentDecisionParser agentDecisionParser() {
        return new JacksonAgentDecisionParser();
    }

    @Bean
    ModelRouter modelRouter(
            ModelProperties properties,
            @Qualifier("primaryModelPort") AgentModelPort primaryModelPort,
            ObjectProvider<AgentModelPort> fallbackModelPort) {
        var routes = new ArrayList<ModelRoute>();
        String primaryId = properties.provider();
        routes.add(new ModelRoute(
                primaryId, modelId(properties, primaryId), Set.of(), primaryModelPort));
        String fallbackId = properties.fallbackProvider();
        var fallback = fallbackModelPort.getIfAvailable();
        if (fallbackId != null && !fallbackId.isBlank() && fallback != null) {
            routes.add(new ModelRoute(
                    fallbackId, modelId(properties, fallbackId), Set.of(), fallback));
        }
        return new ModelRouter(routes);
    }

    @Bean
    com.pulseink.agent.model.ModelPolicy runtimeModelPolicy(ModelProperties properties) {
        var ids = new ArrayList<String>();
        ids.add(properties.provider());
        String fallbackId = properties.fallbackProvider();
        if (fallbackId != null && !fallbackId.isBlank() && !fallbackId.equals(properties.provider())) {
            ids.add(fallbackId);
        }
        return new com.pulseink.agent.model.ModelPolicy(
                List.copyOf(ids), Set.of());
    }

    @Bean
    DirectAgentEngine directAgentEngine(ModelRouter modelRouter,
                                        AgentDecisionParser agentDecisionParser,
                                        com.pulseink.config.properties.AgentRuntimeProperties properties) {
        return new DirectAgentEngine(
                modelRouter, agentDecisionParser,
                properties.maxOutputTokensPerCall(),
                properties.completionTimeout());
    }

    @Bean
    ReactLoop reactLoop(ModelRouter modelRouter,
                        AgentDecisionParser agentDecisionParser,
                        ToolRegistry toolRegistry,
                        com.pulseink.config.properties.AgentRuntimeProperties properties) {
        return new ReactLoop(
                modelRouter, agentDecisionParser, toolRegistry,
                java.time.Clock.systemUTC(),
                properties.maxOutputTokensPerCall(),
                properties.completionTimeout());
    }

    @Bean
    UnifiedAgentRunner unifiedAgentRunner(ReactLoop reactLoop) {
        return new UnifiedAgentRunner(reactLoop);
    }

    @Bean
    com.pulseink.agent.plan.PlanParser planParser() {
        return new com.pulseink.client.model.JacksonPlanParser();
    }

    @Bean
    com.pulseink.agent.plan.PlanValidator planValidator(
            com.pulseink.config.properties.OrchestrationProperties properties) {
        return new com.pulseink.agent.plan.PlanValidator(properties.maxTasks());
    }

    @Bean
    com.pulseink.agent.orchestration.RoleProfileCatalog roleProfileCatalog() {
        return new com.pulseink.client.profile.YamlRoleProfileCatalog("agent-profiles");
    }

    @Bean
    com.pulseink.agent.orchestration.RoleProfileFactory roleProfileFactory(
            com.pulseink.agent.orchestration.RoleProfileCatalog roleProfileCatalog) {
        return new com.pulseink.agent.orchestration.RoleProfileFactory(roleProfileCatalog);
    }

    @Bean
    com.pulseink.agent.orchestration.RoleAgentRunner roleAgentRunner(
            ModelRouter modelRouter,
            com.pulseink.agent.plan.PlanParser planParser,
            com.pulseink.agent.plan.PlanValidator planValidator,
            ReactLoop reactLoop,
            com.pulseink.config.properties.AgentRuntimeProperties properties) {
        return new com.pulseink.agent.orchestration.RoleAgentRunner(
                modelRouter, planParser, planValidator, reactLoop,
                properties.maxOutputTokensPerCall(),
                properties.completionTimeout());
    }

    @Bean(destroyMethod = "close")
    java.util.concurrent.ExecutorService orchestrationExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    com.pulseink.agent.repair.ReviewArtifactInterpreter reviewArtifactInterpreter() {
        return new com.pulseink.agent.repair.StrictReviewArtifactInterpreter();
    }

    @Bean
    com.pulseink.agent.repair.RepairRouter repairRouter() {
        return new com.pulseink.agent.repair.RepairRouter();
    }

    @Bean
    com.pulseink.agent.repair.ArtifactInvalidator artifactInvalidator() {
        return new com.pulseink.agent.repair.ArtifactInvalidator();
    }

    @Bean
    com.pulseink.agent.memory.ContextAssembler contextAssembler(
            com.pulseink.service.memory.MemoryPort memoryPort,
            com.pulseink.config.properties.OrchestrationProperties properties) {
        return new com.pulseink.agent.memory.DefaultContextAssembler(
                memoryPort,
                new com.pulseink.agent.orchestration.ArtifactContextRenderer(
                        properties.maxContextCodePoints()));
    }

    @Bean
    com.pulseink.agent.orchestration.RunCoordinator runCoordinator(
            com.pulseink.agent.orchestration.RoleAgentRunner roleAgentRunner,
            com.pulseink.config.properties.OrchestrationProperties properties,
            com.pulseink.agent.orchestration.RoleProfileFactory roleProfileFactory,
            com.pulseink.agent.plan.PlanParser planParser,
            com.pulseink.agent.plan.PlanValidator planValidator,
            com.pulseink.agent.repair.ReviewArtifactInterpreter reviewArtifactInterpreter,
            com.pulseink.agent.repair.RepairRouter repairRouter,
            com.pulseink.agent.repair.ArtifactInvalidator artifactInvalidator,
            java.util.concurrent.ExecutorService orchestrationExecutor,
            com.pulseink.agent.memory.ContextAssembler contextAssembler) {
        return new com.pulseink.agent.orchestration.RunCoordinator(
                roleAgentRunner,
                planParser,
                planValidator,
                orchestrationExecutor,
                properties.maxParallelReadTasks(),
                properties.maxContextCodePoints(),
                roleProfileFactory,
                properties.plannerMaxModelCalls(),
                reviewArtifactInterpreter,
                repairRouter,
                artifactInvalidator,
                properties.maxRepairRounds(),
                contextAssembler);
    }

    private static String modelId(ModelProperties properties, String provider) {
        return switch (provider) {
            case "fake" -> "pulseink-fake";
            case "ark" -> properties.ark() == null ? "unknown" : properties.ark().model();
            case "zhipu" -> properties.zhipu() == null ? "unknown" : properties.zhipu().model();
            default -> throw new IllegalStateException("unknown model provider: " + provider);
        };
    }
}
