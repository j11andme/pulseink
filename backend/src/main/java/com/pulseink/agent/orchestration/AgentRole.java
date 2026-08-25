package com.pulseink.agent.orchestration;

/**
 * Logical agent roles used by the orchestrated runtime. Each role carries a deterministic
 * tool allowlist via {@link AgentProfile}; a role can never grant itself new tools.
 */
public enum AgentRole {
    PLANNER,
    RESEARCHER,
    STRATEGIST,
    CREATOR,
    REVIEWER
}
