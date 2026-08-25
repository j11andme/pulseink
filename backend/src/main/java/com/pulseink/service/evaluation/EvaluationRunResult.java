package com.pulseink.service.evaluation;

import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;

public record EvaluationRunResult(
        int repetition,
        EvaluationExecution execution,
        EvaluationScore score,
        JudgeScore judge) {

    public EvaluationRunResult {
        if (repetition < 1) throw new IllegalArgumentException("repetition must be positive");
    }

    public ExecutionPolicy policy() { return execution.policy(); }
    public ExecutionMode selectedMode() { return execution.selectedMode(); }
    public String caseId() { return execution.caseId(); }
}
