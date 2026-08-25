package com.pulseink.agent.react;

import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.tool.ToolCall;
import java.util.List;
import java.util.Map;

/**
 * Typed structured decision produced by the model and parsed by {@link AgentDecisionParser}.
 * The engine switches on the sealed subtypes; the raw model JSON is never used as trusted data.
 */
public sealed interface AgentDecision
        permits AgentDecision.ToolCallDecision,
                AgentDecision.FinalDecision,
                AgentDecision.ReplanDecision,
                AgentDecision.NeedApprovalDecision {

    String decisionSummary();

    record ToolCallDecision(
            String decisionSummary,
            ToolCall toolCall) implements AgentDecision {
    }

    record FinalDecision(
            String decisionSummary,
            List<ArtifactSpec> artifacts) implements AgentDecision {
    }

    record ReplanDecision(
            String decisionSummary) implements AgentDecision {
    }

    record NeedApprovalDecision(
            String decisionSummary) implements AgentDecision {
    }

    /**
     * Artifact intent from a FINAL decision, to be materialized by the engine.
     */
    record ArtifactSpec(
            ArtifactType type,
            Map<String, Object> content,
            List<String> sourceRefs) {
        public ArtifactSpec {
            if (type == null) {
                throw new IllegalArgumentException("artifact type must not be null");
            }
            content = content == null ? Map.of() : Map.copyOf(content);
            sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        }
    }
}
