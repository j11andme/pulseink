package com.pulseink.service.evaluation;

import com.pulseink.domain.execution.ExecutionPolicy;

/** Per-case comparison used to make coordination cost visible instead of hiding it in averages. */
public record PolicyAblationComparison(
        String caseId,
        int repetition,
        boolean comparable,
        double qualityDelta,
        double coordinationOverhead,
        ExecutionPolicy preferredPolicy,
        String reason) {}
