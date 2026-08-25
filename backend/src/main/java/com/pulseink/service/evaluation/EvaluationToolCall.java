package com.pulseink.service.evaluation;

import java.util.List;
import java.util.Map;

/** Sanitized tool-call trace used for exact duplicate and argument-policy checks. */
public record EvaluationToolCall(
        int sequence,
        String qualifiedName,
        Map<String, String> arguments,
        String outcome,
        List<String> sourceRefs) {

    public EvaluationToolCall {
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        if (qualifiedName == null || qualifiedName.isBlank()) {
            throw new IllegalArgumentException("qualifiedName required");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        outcome = outcome == null ? "UNKNOWN" : outcome;
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
    }
}
