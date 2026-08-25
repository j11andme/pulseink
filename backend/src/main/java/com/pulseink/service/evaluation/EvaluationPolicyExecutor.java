package com.pulseink.service.evaluation;

import com.pulseink.domain.execution.ExecutionPolicy;

@FunctionalInterface
public interface EvaluationPolicyExecutor {
    EvaluationExecution execute(EvaluationCase testCase, ExecutionPolicy policy);

    default EvaluationRuntimeDescriptor runtimeDescriptor() {
        return EvaluationRuntimeDescriptor.unknown();
    }
}
