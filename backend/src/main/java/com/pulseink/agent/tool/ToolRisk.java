package com.pulseink.agent.tool;

/**
 * Risk classification enforced by {@code ToolPolicy} before any Provider invocation.
 */
public enum ToolRisk {
    READ,
    WRITE,
    EXTERNAL_SIDE_EFFECT,
    SECRET
}
