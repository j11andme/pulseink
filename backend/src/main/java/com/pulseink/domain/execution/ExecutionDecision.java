package com.pulseink.domain.execution;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExecutionDecision(
        ExecutionMode selectedMode,
        String selectorPolicyVersion,
        List<String> reasonCodes,
        Map<String, Object> featureSnapshot,
        long estimatedTokenBudget) {

    public ExecutionDecision {
        selectedMode = Objects.requireNonNull(selectedMode, "selected mode must not be null");
        if (selectorPolicyVersion == null || selectorPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("selector policy version must not be blank");
        }
        reasonCodes =
                List.copyOf(Objects.requireNonNull(reasonCodes, "reason codes must not be null"));
        featureSnapshot = Map.copyOf(
                Objects.requireNonNull(featureSnapshot, "feature snapshot must not be null"));
        if (estimatedTokenBudget <= 0) {
            throw new IllegalArgumentException("estimated token budget must be positive");
        }
    }
}
