package com.pulseink.client.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.tool.ToolCall;
import com.pulseink.domain.knowledge.EvidenceAuthority;
import com.pulseink.domain.knowledge.EvidenceChunk;
import com.pulseink.domain.knowledge.KnowledgeDocumentStatus;
import com.pulseink.domain.knowledge.KnowledgeType;
import com.pulseink.service.knowledge.KnowledgeSearchService.SearchResult;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase;
import com.pulseink.service.knowledge.QueryKnowledgeUseCase.DocumentPage;
import com.pulseink.service.knowledge.RetrievalMode;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeSearchToolTest {

    private final FakeQuery useCase = new FakeQuery();
    private final KnowledgeSearchTool tool = new KnowledgeSearchTool(useCase);

    @Test
    void invokesQueryUseCaseAndReturnsCompactJsonWithoutSecrets() {
        useCase.result = new SearchResult(RetrievalMode.HYBRID, null, List.of(
                new EvidenceChunk("src-1", 1L, 1, "chunk-1", "Guide", "Guide > Colors",
                        "The brand color is blue.", 0.9, Set.of("LEXICAL"),
                        KnowledgeType.BRAND_GUIDELINE, EvidenceAuthority.OFFICIAL,
                        Instant.parse("2026-08-11T10:00:00Z"))));

        var result = tool.validate(ToolCall.of("builtin.knowledge_search",
                java.util.Map.of("query", "brand color")), java.time.Duration.ofSeconds(5));

        String json = result.contentText();
        assertThat(json).contains("\"retrievalMode\":\"HYBRID\"");
        assertThat(json).contains("\"sourceId\":\"src-1\"");
        assertThat(json).contains("\"title\":\"Guide\"");
        assertThat(json).contains("\"snippet\":\"The brand color is blue.\"");
        assertThat(json).contains("\"channels\":[\"LEXICAL\"]");
        assertThat(json).contains("\"untrustedContent\":true");
        assertThat(json).doesNotContain("storageKey").doesNotContain("/data/")
                .doesNotContain("fullText").doesNotContain("vector");
        assertThat(result.metadata()).containsEntry("sourceRefs", "src-1");
        assertThat(useCase.lastQuery()).isEqualTo("brand color");
    }

    @Test
    void rejectsMissingQueryAndInvalidTopK() {
        assertThatThrownBy(() -> tool.validate(
                ToolCall.of("builtin.knowledge_search", java.util.Map.of()),
                java.time.Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validate(
                ToolCall.of("builtin.knowledge_search",
                        java.util.Map.of("query", "q", "topK", 99)),
                java.time.Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyResultIsSuccessfulEmptyEvidence() {
        useCase.result = new SearchResult(RetrievalMode.HYBRID, null, List.of());
        var result = tool.validate(ToolCall.of("builtin.knowledge_search",
                java.util.Map.of("query", "nothing")), java.time.Duration.ofSeconds(5));
        assertThat(result.contentText()).contains("\"evidence\":[]");
    }

    static final class FakeQuery implements QueryKnowledgeUseCase {
        SearchResult result;
        private String lastQuery;

        @Override
        public DocumentPage list(KnowledgeDocumentStatus status, KnowledgeType type,
                                 int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SearchResult search(String query, List<KnowledgeType> types,
                                   List<EvidenceAuthority> authorities,
                                   Instant updatedAfter, int topK) {
            lastQuery = query;
            return result;
        }

        String lastQuery() {
            return lastQuery;
        }
    }
}
