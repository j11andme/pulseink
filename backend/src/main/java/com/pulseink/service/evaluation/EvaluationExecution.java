package com.pulseink.service.evaluation;

import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Facts captured from one policy execution; it never contains prompts or hidden reasoning. */
public record EvaluationExecution(
        String caseId,
        ExecutionPolicy policy,
        ExecutionMode selectedMode,
        String finalState,
        AgentTerminalReason terminalReason,
        List<String> retrievedChunkIds,
        List<String> sourceRefs,
        Set<String> observedTools,
        int modelCalls,
        int toolCalls,
        long totalTokens,
        int reactRounds,
        int repairCount,
        int coordinationArtifacts,
        long latencyMs,
        String candidateText,
        List<EvaluationToolCall> toolTrace,
        List<EvaluationTraceStep> trace) {

    public EvaluationExecution {
        if (caseId == null || caseId.isBlank()) throw new IllegalArgumentException("caseId required");
        policy = Objects.requireNonNull(policy);
        selectedMode = Objects.requireNonNull(selectedMode);
        if (finalState == null || finalState.isBlank()) throw new IllegalArgumentException("finalState required");
        terminalReason = Objects.requireNonNull(terminalReason);
        retrievedChunkIds = retrievedChunkIds == null ? List.of() : List.copyOf(retrievedChunkIds);
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        observedTools = observedTools == null ? Set.of() : Set.copyOf(observedTools);
        if (modelCalls < 0 || toolCalls < 0 || totalTokens < 0 || reactRounds < 0
                || repairCount < 0 || coordinationArtifacts < 0 || latencyMs < 0) {
            throw new IllegalArgumentException("execution metrics must not be negative");
        }
        candidateText = candidateText == null ? "" : candidateText;
        toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        trace = trace == null ? List.of() : List.copyOf(trace);
    }

    public EvaluationExecution(
            String caseId, ExecutionPolicy policy, ExecutionMode selectedMode,
            String finalState, AgentTerminalReason terminalReason,
            List<String> retrievedChunkIds, List<String> sourceRefs,
            Set<String> observedTools, int modelCalls, int toolCalls, long totalTokens,
            int reactRounds, int repairCount, int coordinationArtifacts, long latencyMs) {
        this(caseId, policy, selectedMode, finalState, terminalReason, retrievedChunkIds,
                sourceRefs, observedTools, modelCalls, toolCalls, totalTokens, reactRounds,
                repairCount, coordinationArtifacts, latencyMs, "", List.of(), List.of());
    }

    public EvaluationExecution(
            String caseId, ExecutionPolicy policy, ExecutionMode selectedMode,
            String finalState, AgentTerminalReason terminalReason,
            List<String> retrievedChunkIds, List<String> sourceRefs,
            Set<String> observedTools, int modelCalls, int toolCalls, long totalTokens,
            int reactRounds, int repairCount, int coordinationArtifacts, long latencyMs,
            String candidateText) {
        this(caseId, policy, selectedMode, finalState, terminalReason, retrievedChunkIds,
                sourceRefs, observedTools, modelCalls, toolCalls, totalTokens, reactRounds,
                repairCount, coordinationArtifacts, latencyMs, candidateText,
                List.of(), List.of());
    }
}
