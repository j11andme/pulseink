package com.pulseink.service.evaluation;

import java.util.List;

public record EvaluationScore(
        EvaluationSampleStatus status,
        boolean passed,
        boolean hardRulesPassed,
        boolean qualityScored,
        double quality,
        double groundedness,
        double recallAtK,
        double precisionAtK,
        double mrr,
        double ndcg,
        double trajectory,
        long totalTokens,
        long latencyMs,
        int coordinationArtifacts,
        List<String> violations,
        EvaluationFailure failure) {

    public EvaluationScore {
        status = status == null ? EvaluationSampleStatus.SCORED : status;
        violations = violations == null ? List.of() : List.copyOf(violations);
        failure = failure == null ? EvaluationFailure.none() : failure;
    }

    public EvaluationScore withEfficiency(long tokens, long latency, int coordination) {
        return new EvaluationScore(status, passed, hardRulesPassed, qualityScored,
                quality, groundedness,
                recallAtK, precisionAtK, mrr, ndcg, trajectory,
                tokens, latency, coordination, violations, failure);
    }
}
