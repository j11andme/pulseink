package com.pulseink.agent.model;

/**
 * Model capabilities used by {@link ModelRouter} to match a route to an {@code AgentProfile}.
 */
public enum ModelCapability {
    REASONING_STRONG,
    TOOL_FAST,
    WRITING_LONG_CONTEXT,
    REVIEW_STRICT
}
