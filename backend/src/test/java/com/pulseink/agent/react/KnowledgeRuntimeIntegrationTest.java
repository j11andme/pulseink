package com.pulseink.agent.react;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
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
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.client.model.FakeModelAdapter;
import com.pulseink.client.model.JacksonAgentDecisionParser;
import com.pulseink.client.tool.JavaToolProvider;
import com.pulseink.client.tool.KnowledgeSearchTool;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeRuntimeIntegrationTest {

    private static final String SEARCH = "builtin.knowledge_search";
    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    @Test
    void reactAgentCreatesEvidencePackFromGovernedKnowledgeSearch() {
        var queryUseCase = new FakeQuery(new SearchResult(RetrievalMode.HYBRID, null, List.of(
                new EvidenceChunk("src-1", 1L, 1, "chunk-1", "Guide", "Guide > Colors",
                        "The brand color is blue.", 0.9, Set.of("LEXICAL"),
                        KnowledgeType.BRAND_GUIDELINE, EvidenceAuthority.OFFICIAL, NOW))));
        var registry = registryWith(queryUseCase);
        var fake = new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"TOOL_CALL","decisionSummary":"search knowledge",
                         "toolCall":{"qualifiedName":"builtin.knowledge_search",
                                     "arguments":{"query":"brand color"}}}
                        """),
                FakeModelAdapter.Scene.of("""
                        {"decision":"FINAL","decisionSummary":"evidence ready",
                         "artifacts":[{"type":"EVIDENCE_PACK",
                           "content":{"summary":"blue brand color"},
                           "sourceRefs":["src-1"]}]}
                        """)));
        var loop = loopWith(fake, registry);
        var events = new ArrayList<AgentRuntimeEvent>();

        AgentExecutionResult result = loop.execute(
                request(profileWith(Set.of(SEARCH)), List.of()), events::add);

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.SUCCEEDED);
        assertThat(result.artifacts()).hasSize(1);
        assertThat(result.artifacts().get(0).type())
                .isEqualTo(com.pulseink.agent.artifact.ArtifactType.EVIDENCE_PACK);
        assertThat(result.artifacts().get(0).sourceRefs()).containsExactly("src-1");
        assertThat(events).anyMatch(AgentRuntimeEvent.ToolCallCompleted.class::isInstance);
        assertThat(queryUseCase.lastQuery).isEqualTo("brand color");
    }

    @Test
    void directCannotSeeOrInvokeKnowledgeSearch() {
        var registry = registryWith(new FakeQuery(new SearchResult(RetrievalMode.HYBRID, null, List.of())));
        var directProfile = AgentProfile.unified(
                "unified", Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultDirect(Instant.now().plus(Duration.ofMinutes(30))));
        assertThat(registry.schemasFor(directProfile)).isEmpty();

        var loop = loopWith(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"TOOL_CALL","decisionSummary":"search",
                         "toolCall":{"qualifiedName":"builtin.knowledge_search",
                                     "arguments":{"query":"x"}}}
                        """))), registry);
        var result = loop.execute(request(profileWith(Set.of()), List.of()), event -> {});
        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.TOOL_FAILURE);
    }

    @Test
    void invalidSchemaIsRejectedBeforeProvider() {
        var queryUseCase = new FakeQuery(new SearchResult(RetrievalMode.HYBRID, null, List.of()));
        var registry = registryWith(queryUseCase);
        var loop = loopWith(new FakeModelAdapter(List.of(
                FakeModelAdapter.Scene.of("""
                        {"decision":"TOOL_CALL","decisionSummary":"search",
                         "toolCall":{"qualifiedName":"builtin.knowledge_search",
                                     "arguments":{"topK":5}}}
                        """))), registry);

        var result = loop.execute(request(profileWith(Set.of(SEARCH)), List.of()), event -> {});
        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.TOOL_FAILURE);
        assertThat(queryUseCase.calls).isZero();
    }

    @Test
    void agentCannotFabricateUnretrievedSourceRefs() {
        var queryUseCase = new FakeQuery(new SearchResult(RetrievalMode.HYBRID, null, List.of()));
        var registry = registryWith(queryUseCase);
        var fabricated = FakeModelAdapter.Scene.of("""
                {"decision":"FINAL","decisionSummary":"fabricated",
                 "artifacts":[{"type":"EVIDENCE_PACK",
                   "content":{"summary":"made up"},
                   "sourceRefs":["never-searched"]}]}
                """);
        var fake = new FakeModelAdapter(List.of(fabricated, fabricated));
        var loop = loopWith(fake, registry);

        AgentExecutionResult result = loop.execute(
                request(profileWith(Set.of(SEARCH)), List.of()), event -> {});

        assertThat(result.terminalReason()).isEqualTo(AgentTerminalReason.INVALID_MODEL_OUTPUT);
        assertThat(queryUseCase.calls).isZero();
        assertThat(result.artifacts()).isEmpty();
    }

    private static ToolRegistry registryWith(QueryKnowledgeUseCase queryUseCase) {
        var searchTool = new KnowledgeSearchTool(queryUseCase);
        var schema = ToolDefinition.Schema.of(
                Map.of("query", ToolDefinition.PropertySpec.of("string"),
                        "topK", ToolDefinition.PropertySpec.of("integer")),
                Set.of("query"), false);
        var provider = new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "knowledge_search", "search",
                                schema, ToolRisk.READ),
                        searchTool::validate)));
        return new ToolRegistry(List.of(provider));
    }

    private static ReactLoop loopWith(FakeModelAdapter fake, ToolRegistry registry) {
        var route = new ModelRoute("fake", "pulseink-fake", Set.of(), fake);
        return new ReactLoop(
                new ModelRouter(List.of(route)),
                new JacksonAgentDecisionParser(),
                registry,
                new BudgetTracker.MutableClock(Instant.now()));
    }

    private static AgentProfile profileWith(Set<String> tools) {
        return AgentProfile.unified(
                "unified", tools,
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));
    }

    private static AgentExecutionRequest request(AgentProfile profile,
                                                 List<com.pulseink.agent.artifact.AgentArtifact> prior) {
        return new AgentExecutionRequest(
                1L, "req-1", ExecutionMode.REACT, profile,
                "objective", prior, BudgetSnapshot.ZERO, ApprovalState.NOT_REQUIRED);
    }

    static final class FakeQuery implements QueryKnowledgeUseCase {
        final SearchResult result;
        String lastQuery;
        int calls;

        FakeQuery(SearchResult result) {
            this.result = result;
        }

        @Override
        public DocumentPage list(KnowledgeDocumentStatus status, KnowledgeType type,
                                 int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SearchResult search(String query, List<KnowledgeType> types,
                                   List<EvidenceAuthority> authorities,
                                   Instant updatedAfter, int topK) {
            calls++;
            lastQuery = query;
            return result;
        }
    }
}
