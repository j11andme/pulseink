package com.pulseink.agent.memory;

/**
 * Fixed section order of every rendered context. A section that a role policy excludes is
 * simply not rendered; order can never vary per input.
 */
public enum ContextSection {
    BRIEF,
    CURRENT_OBJECTIVE,
    WORKING_MEMORY,
    DEPENDENCY_ARTIFACTS,
    EPISODIC_SUMMARY,
    APPROVED_INSIGHTS,
    SOURCE_LABELS
}
