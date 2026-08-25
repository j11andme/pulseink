package com.pulseink.agent.selection;

public record SelectorPolicy(String version, long maxTokenBudget) {

    public static final SelectorPolicy V1 = new SelectorPolicy("selector-v1", 64_000L);

    public SelectorPolicy {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("policy version must not be blank");
        }
        if (maxTokenBudget <= 0) {
            throw new IllegalArgumentException("max token budget must be positive");
        }
    }
}
