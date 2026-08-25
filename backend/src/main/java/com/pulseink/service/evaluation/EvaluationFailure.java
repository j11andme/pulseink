package com.pulseink.service.evaluation;

import java.util.List;

/** Stable, sanitized explanation attached to every abnormal evaluation result. */
public record EvaluationFailure(
        EvaluationFailureStage stage,
        String code,
        String summary,
        List<String> evidence) {

    public EvaluationFailure {
        stage = stage == null ? EvaluationFailureStage.NONE : stage;
        code = code == null ? "" : code;
        summary = summary == null ? "" : summary;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static EvaluationFailure none() {
        return new EvaluationFailure(EvaluationFailureStage.NONE, "", "", List.of());
    }
}
