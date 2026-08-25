package com.pulseink.domain.execution;

public record TaskProperties(
        double decomposability,
        int channelCount,
        int sourceDiversity,
        int parallelResearchBranches,
        double sequentialDependency,
        double factualRisk,
        int toolBreadth,
        long latencyBudgetMs) {

    public TaskProperties {
        requireProbability(decomposability, "decomposability");
        requireProbability(sequentialDependency, "sequential dependency");
        requireProbability(factualRisk, "factual risk");
        if (channelCount < 1) {
            throw new IllegalArgumentException("channel count must be positive");
        }
        if (sourceDiversity < 0) {
            throw new IllegalArgumentException("source diversity must not be negative");
        }
        if (parallelResearchBranches < 0) {
            throw new IllegalArgumentException("parallel research branches must not be negative");
        }
        if (toolBreadth < 0) {
            throw new IllegalArgumentException("tool breadth must not be negative");
        }
        if (latencyBudgetMs <= 0) {
            throw new IllegalArgumentException("latency budget must be positive");
        }
    }

    private static void requireProbability(double value, String field) {
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }
}
