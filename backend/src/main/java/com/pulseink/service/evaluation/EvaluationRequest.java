package com.pulseink.service.evaluation;

import com.pulseink.domain.execution.ExecutionPolicy;
import java.util.List;
import java.util.Objects;

public record EvaluationRequest(
        EvaluationSuite suite,
        List<ExecutionPolicy> policies,
        boolean judgeEnabled) {

    public EvaluationRequest {
        suite = Objects.requireNonNull(suite, "suite must not be null");
        policies = policies == null || policies.isEmpty()
                ? List.of(ExecutionPolicy.DIRECT, ExecutionPolicy.REACT,
                        ExecutionPolicy.ORCHESTRATED, ExecutionPolicy.ADAPTIVE)
                : List.copyOf(policies);
        if (policies.stream().distinct().count() != policies.size()) {
            throw new IllegalArgumentException("policies must not contain duplicates");
        }
    }
}
