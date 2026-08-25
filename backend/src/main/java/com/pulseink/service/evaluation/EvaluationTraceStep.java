package com.pulseink.service.evaluation;

import java.time.Instant;
import java.util.List;

/** Compact observable trajectory step. Prompts and chain-of-thought are deliberately excluded. */
public record EvaluationTraceStep(
        int sequence,
        Instant timestamp,
        String eventType,
        String actor,
        String subject,
        String outcome,
        String summary,
        List<String> evidence) {

    public EvaluationTraceStep {
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        eventType = text(eventType, "eventType");
        actor = actor == null ? "runtime" : actor;
        subject = subject == null ? "" : subject;
        outcome = outcome == null ? "OBSERVED" : outcome;
        summary = summary == null ? "" : summary;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
        return value;
    }
}
