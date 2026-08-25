package com.pulseink.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.api.AgentExecutionObserver;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.BudgetTracker;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRoute;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.orchestration.RoleAgentRunner;
import com.pulseink.agent.orchestration.RoleProfileFactory;
import com.pulseink.agent.orchestration.RunCoordinator;
import com.pulseink.agent.plan.PlanValidator;
import com.pulseink.agent.react.AgentDecision;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.client.model.JacksonPlanParser;
import com.pulseink.client.tool.DeterministicValidateTool;
import com.pulseink.client.tool.JavaToolProvider;
import com.pulseink.client.tool.KnowledgeSearchTool;
import com.pulseink.config.ModelConfiguration;
import com.pulseink.config.ModelProperties;
import com.pulseink.config.properties.KnowledgeProperties;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.EvidenceChunk;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.knowledge.KnowledgeSearchService.SearchResult;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase;
import com.pulseink.service.knowledge.RetrievalMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * Real Volcano Ark orchestration smoke. Explicitly enabled with
 * {@code -Dpulseink.real-model-smoke=true}; otherwise skipped. Uses a minimal Spring context so
 * the root .env feeds the ark configuration, and a fixed in-test evidence stub so no real
 * Elasticsearch data is required. Never prints prompts, Authorization headers or provider bodies.
 */
@Tag("real-model")
@EnabledIfSystemProperty(named = "pulseink.real-model-smoke", matches = "true")
class ArkOrchestratedSmokeIT {

    private static ConfigurableApplicationContext context;

    @BeforeAll
    static void startContext() {
        var application = new SpringApplication(MinimalSmokeConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of(
                "spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration"));
        context = application.run();
    }

    @AfterAll
    static void stopContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void fiveRoleOrchestratedRunSucceedsOnRealArk() {
        AgentModelPort primary;
        try {
            primary = context.getBean("primaryModelPort", AgentModelPort.class);
        } catch (Exception ex) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED_REAL_PROVIDER: primary model port unavailable (provider is not ark)", ex);
        }
        String providerId = context.getEnvironment().getProperty("pulseink.model.provider");
        if (!"ark".equals(providerId)) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED_REAL_PROVIDER: PULSEINK_MODEL_PROVIDER=" + providerId);
        }

        var stub = new QueryKnowledgeUseCase() {
            @Override
            public DocumentPage list(KnowledgeDocumentStatus status, KnowledgeType type,
                                     int page, int size) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SearchResult search(String query, List<KnowledgeType> types,
                                       List<EvidenceAuthority> authorities,
                                       Instant updatedAfter, int topK) {
                return new SearchResult(RetrievalMode.HYBRID, null, List.of(
                        new EvidenceChunk("stub-1", 1L, 1, "chunk-1", "Stub Guide",
                                "Stub > Facts", "The brand color is blue.", 0.9,
                                Set.of("LEXICAL"), KnowledgeType.BRAND_GUIDELINE,
                                EvidenceAuthority.OFFICIAL, Instant.now())));
            }
        };
        var searchTool = new KnowledgeSearchTool(stub);
        var validateTool = new DeterministicValidateTool();
        var validateSchema = ToolDefinition.Schema.of(
                Map.of("content", ToolDefinition.PropertySpec.of("string")),
                Set.of("content"), false);
        var searchSchema = ToolDefinition.Schema.of(
                Map.of("query", ToolDefinition.PropertySpec.of("string"),
                        "topK", ToolDefinition.PropertySpec.of("integer")),
                Set.of("query"), false);
        var registry = new ToolRegistry(List.of(new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "deterministic_validate", "validate",
                                validateSchema, ToolRisk.READ),
                        validateTool::validate),
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "knowledge_search", "search",
                                searchSchema, ToolRisk.READ),
                        searchTool::validate)))));

        var policy = new ModelPolicy(List.of(providerId), Set.of());
        var providerFailures = Collections.synchronizedList(new ArrayList<String>());
        var providerCompletions = Collections.synchronizedList(new ArrayList<String>());
        var responseBuffers = new ConcurrentHashMap<String, StringBuffer>();
        var decisionSummaries = Collections.synchronizedList(new ArrayList<String>());
        var diagnosticParser = new JacksonAgentDecisionParser();
        AgentModelPort observedPrimary = (modelRequest, eventConsumer) -> primary.stream(
                modelRequest,
                event -> {
                    if (event instanceof ModelStreamEvent.Failed failed) {
                        providerFailures.add(failed.code() + ": " + failed.message());
                    }
                    if (event instanceof ModelStreamEvent.ContentDelta delta) {
                        responseBuffers.computeIfAbsent(
                                modelRequest.requestId(), ignored -> new StringBuffer())
                                .append(delta.content());
                    }
                    if (event instanceof ModelStreamEvent.Completed completed) {
                        providerCompletions.add(
                                modelRequest.requestId() + ":" + completed.finishReason());
                        decisionSummaries.add(summarizeDecision(
                                modelRequest.requestId(),
                                responseBuffers.get(modelRequest.requestId()),
                                diagnosticParser));
                    }
                    eventConsumer.accept(event);
                });
        var route = new ModelRoute(providerId, modelId(), Set.of(), observedPrimary);
        var router = new ModelRouter(List.of(route));
        var reactLoop = new ReactLoop(router, new JacksonAgentDecisionParser(), registry,
                new BudgetTracker.MutableClock(Instant.now()));
        var runner = new RoleAgentRunner(router, new JacksonPlanParser(),
                new PlanValidator(12), reactLoop);
        var profileFactory = new RoleProfileFactory(
                new com.pulseink.client.profile.YamlRoleProfileCatalog("agent-profiles"));
        var now = Instant.now();
        var deadline = now.plus(Duration.ofMinutes(5));
        var coordinator = new RunCoordinator(
                runner,
                new JacksonPlanParser(),
                new PlanValidator(12),
                Executors.newVirtualThreadPerTaskExecutor(),
                3, 12000, profileFactory, 3);

        var request = new AgentExecutionRequest(
                1L, "run-smoke", ExecutionMode.ORCHESTRATED,
                AgentProfile.role("smoke", AgentRole.CREATOR, Set.of(), policy,
                        new ExecutionBudget(100, 100, 200_000L, 100, 1, deadline), "Smoke.",
                        Set.of(), 100, 100, 100),
                "objective=launch PulseInk campaign; audience=Java developers; "
                        + "channels=[BLOG, SOCIAL]; constraints=[facts need citations]; "
                        + "reasonCodes=[MANUAL_POLICY_OVERRIDE]; featureSnapshot={factualRisk:0.9}",
                List.of(), BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);

        var events = new ArrayList<AgentRuntimeEvent>();
        AgentExecutionResult result;
        try {
            result = coordinator.execute(request, events::add);
        } catch (RuntimeException ex) {
            throw new org.opentest4j.TestAbortedException(
                    "BLOCKED_REAL_PROVIDER: " + sanitized(ex), ex);
        }

        var startedRoles = events.stream()
                .filter(AgentRuntimeEvent.TaskStarted.class::isInstance)
                .map(e -> ((AgentRuntimeEvent.TaskStarted) e).role().name())
                .toList();
        var artifactTypes = result.artifacts().stream()
                .map(a -> a.type().name())
                .toList();

        assertThat(result.terminalReason())
                .as("terminal reason; roles=%s artifacts=%s providerFailures=%s completions=%s decisions=%s",
                        startedRoles, artifactTypes, providerFailures,
                        providerCompletions, decisionSummaries)
                .isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(startedRoles).contains(
                "PLANNER", "RESEARCHER", "STRATEGIST", "CREATOR", "REVIEWER");
        assertThat(artifactTypes).contains(
                "PLAN", "EVIDENCE_PACK", "CONTENT_STRATEGY",
                "CONTENT_DRAFT", "REVIEW_REPORT");
        assertThat(result.artifacts().stream()
                .flatMap(a -> a.sourceRefs().stream()))
                .contains("stub-1");
    }

    private String modelId() {
        return String.valueOf(context.getEnvironment()
                .getProperty("pulseink.model.ark.model", "unknown"));
    }

    private static String sanitized(Exception ex) {
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    private static String summarizeDecision(
            String requestId,
            StringBuffer response,
            JacksonAgentDecisionParser parser) {
        if (response == null || response.isEmpty()) {
            return requestId + ":EMPTY";
        }
        try {
            return switch (parser.parse(response.toString())) {
                case AgentDecision.ToolCallDecision tool ->
                        requestId + ":TOOL_CALL:" + tool.toolCall().qualifiedName();
                case AgentDecision.FinalDecision finalDecision -> requestId + ":FINAL:"
                        + finalDecision.artifacts().stream()
                                .map(spec -> spec.type().name())
                                .toList();
                case AgentDecision.ReplanDecision ignored -> requestId + ":REPLAN";
                case AgentDecision.NeedApprovalDecision ignored ->
                        requestId + ":NEED_APPROVAL";
            };
        } catch (IllegalArgumentException invalid) {
            return requestId + ":INVALID:" + invalid.getMessage();
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.Import(ModelConfiguration.class)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(ModelProperties.class)
    static class MinimalSmokeConfig {
    }
}
