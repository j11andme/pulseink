package com.pulseink.service.evaluation;

import com.pulseink.domain.execution.ExecutionPolicy;

public record PolicySummary(
        ExecutionPolicy policy,
        int executions,
        int scoredExecutions,
        int errors,
        int passed,
        double passRate,
        int qualitySamples,
        int judgeUnscored,
        double averageQuality,
        double averageGroundedness,
        double averageTokens,
        double averageLatencyMs,
        double averageCoordinationArtifacts,
        double qualityStdDev,
        double latencyStdDev) {}
