package com.pulseink.service.evaluation;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.util.List;
import java.util.Objects;

/** User-defined, single-case evaluation input. It never mutates the fixed case catalog. */
public record CustomEvaluationRequest(
        String task,
        String expectedResult,
        String audience,
        CampaignChannel channel,
        List<String> constraints,
        List<ExecutionPolicy> policies) {

    public CustomEvaluationRequest {
        task = text(task, "task", 2_000);
        expectedResult = text(expectedResult, "expectedResult", 4_000);
        audience = text(audience, "audience", 200);
        channel = Objects.requireNonNull(channel, "channel must not be null");
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        if (constraints.size() > 10 || constraints.stream().anyMatch(value ->
                value == null || value.isBlank() || value.length() > 300)) {
            throw new IllegalArgumentException(
                    "constraints must contain at most 10 non-blank values of 300 characters");
        }
        policies = policies == null ? List.of() : List.copyOf(policies);
        if (policies.isEmpty() || policies.stream().anyMatch(Objects::isNull)
                || policies.stream().distinct().count() != policies.size()) {
            throw new IllegalArgumentException(
                    "policies must contain unique execution policies");
        }
    }

    private static String text(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}
