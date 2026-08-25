package com.pulseink.client.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.react.AgentDecision;
import com.pulseink.agent.react.AgentDecisionParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JacksonAgentDecisionParserTest {

    private final AgentDecisionParser parser = new JacksonAgentDecisionParser();

    @Test
    void parsesToolCallDecision() {
        var decision = parser.parse("""
                {
                  "decision": "TOOL_CALL",
                  "decisionSummary": "validate the draft",
                  "toolCall": {
                    "qualifiedName": "builtin.deterministic_validate",
                    "arguments": {"content": "hello"}
                  }
                }
                """);
        assertThat(decision).isInstanceOf(AgentDecision.ToolCallDecision.class);
        var toolCall = (AgentDecision.ToolCallDecision) decision;
        assertThat(toolCall.decisionSummary()).isEqualTo("validate the draft");
        assertThat(toolCall.toolCall().qualifiedName())
                .isEqualTo("builtin.deterministic_validate");
        assertThat(toolCall.toolCall().arguments()).containsEntry("content", "hello");
    }

    @Test
    void parsesFinalDecisionWithArtifacts() {
        var decision = parser.parse("""
                {
                  "decision": "FINAL",
                  "decisionSummary": "draft ready",
                  "artifacts": [
                    {
                      "type": "CONTENT_DRAFT",
                      "content": {"title": "Hello"},
                      "sourceRefs": ["ref-1"]
                    }
                  ]
                }
                """);
        assertThat(decision).isInstanceOf(AgentDecision.FinalDecision.class);
        var finalDecision = (AgentDecision.FinalDecision) decision;
        assertThat(finalDecision.artifacts()).hasSize(1);
        assertThat(finalDecision.artifacts().get(0).type())
                .isEqualTo(com.pulseink.agent.artifact.ArtifactType.CONTENT_DRAFT);
        assertThat(finalDecision.artifacts().get(0).content())
                .containsEntry("title", "Hello");
    }

    @Test
    void parsesReplanAndNeedApproval() {
        assertThat(parser.parse("""
                {"decision": "REPLAN", "decisionSummary": "need more context"}
                """))
                .isInstanceOf(AgentDecision.ReplanDecision.class);
        assertThat(parser.parse("""
                {"decision": "NEED_APPROVAL", "decisionSummary": "requires approval"}
                """))
                .isInstanceOf(AgentDecision.NeedApprovalDecision.class);
    }

    @Test
    void toolCallRequiresExactlyOneToolCall() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision": "TOOL_CALL", "decisionSummary": "missing tool call"}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {
                  "decision": "TOOL_CALL",
                  "decisionSummary": "two tool calls",
                  "toolCall": {"qualifiedName": "builtin.a", "arguments": {}},
                  "artifacts": [{"type": "CONTENT_DRAFT", "content": {"k": "v"}}]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finalRequiresNonEmptyArtifacts() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision": "FINAL", "decisionSummary": "no artifacts"}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"decision": "FINAL", "decisionSummary": "empty", "artifacts": []}
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replanAndNeedApprovalRejectToolsAndArtifacts() {
        assertThatThrownBy(() -> parser.parse("""
                {
                  "decision": "REPLAN",
                  "decisionSummary": "with tool",
                  "toolCall": {"qualifiedName": "builtin.a", "arguments": {}}
                }
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {
                  "decision": "NEED_APPROVAL",
                  "decisionSummary": "with artifact",
                  "artifacts": [{"type": "CONTENT_DRAFT", "content": {"k": "v"}}]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownDecisionAndType() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision": "UNKNOWN", "decisionSummary": "x"}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {
                  "decision": "FINAL",
                  "decisionSummary": "bad type",
                  "artifacts": [{"type": "NOT_A_TYPE", "content": {"k": "v"}}]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankSummary() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision": "REPLAN", "decisionSummary": "  "}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"decision": "REPLAN"}
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateArtifactTypes() {
        assertThatThrownBy(() -> parser.parse("""
                {
                  "decision": "FINAL",
                  "decisionSummary": "duplicate",
                  "artifacts": [
                    {"type": "CONTENT_DRAFT", "content": {"a": 1}},
                    {"type": "CONTENT_DRAFT", "content": {"b": 2}}
                  ]
                }
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownFields() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision": "REPLAN", "decisionSummary": "x", "sneaky": "field"}
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTypedNullAndOversizedPayload() {
        assertThatThrownBy(() -> parser.parse("""
                {"decision": null, "decisionSummary": "x"}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {
                  "decision":"TOOL_CALL",
                  "decisionSummary":"null argument",
                  "toolCall":{"qualifiedName":"builtin.a","arguments":{"token":null}}
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null JSON values");
        var oversized = "{\"decision\":\"REPLAN\",\"decisionSummary\":\"" + "x".repeat(80_000) + "\"}";
        assertThatThrownBy(() -> parser.parse(oversized))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSummaryLongerThanTwoThousandCharacters() {
        var summary = "x".repeat(2001);
        assertThatThrownBy(() -> parser.parse(
                "{\"decision\":\"REPLAN\",\"decisionSummary\":\"" + summary + "\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyAnExactSingleJsonFenceEnvelope() {
        assertThat(parser.parse("""
                ```json
                {"decision":"REPLAN","decisionSummary":"retry"}
                ```
                """)).isInstanceOf(AgentDecision.ReplanDecision.class);
        assertThatThrownBy(() -> parser.parse("""
                explanation
                ```json
                {"decision":"REPLAN","decisionSummary":"retry"}
                ```
                """)).isInstanceOf(IllegalArgumentException.class);
    }
}
