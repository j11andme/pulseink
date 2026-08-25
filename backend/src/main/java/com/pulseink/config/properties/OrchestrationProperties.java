package com.pulseink.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Orchestration limits. All values are positive integers.
 */
@ConfigurationProperties("pulseink.orchestration")
public record OrchestrationProperties(
        int maxTasks,
        int maxParallelReadTasks,
        int maxContextCodePoints,
        int plannerMaxModelCalls,
        int maxRepairRounds) {

    public OrchestrationProperties {
        if (maxTasks < 0 || maxParallelReadTasks < 0
                || maxContextCodePoints < 0 || plannerMaxModelCalls < 0
                || maxRepairRounds < 0 || maxRepairRounds > 2) {
            throw new IllegalArgumentException(
                    "orchestration properties must not be negative");
        }
        if (maxTasks == 0) {
            maxTasks = 12;
        }
        if (maxParallelReadTasks == 0) {
            maxParallelReadTasks = 3;
        }
        if (maxContextCodePoints == 0) {
            maxContextCodePoints = 12000;
        }
        if (plannerMaxModelCalls == 0) {
            plannerMaxModelCalls = 3;
        }
    }
}
