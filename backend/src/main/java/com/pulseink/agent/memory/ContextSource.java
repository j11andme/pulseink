package com.pulseink.agent.memory;

/**
 * One context section source, used to render the deterministic SOURCE_LABELS section so the
 * model can see where facts came from without raw repository keys or vectors.
 */
public record ContextSource(
        String kind,
        String label,
        String referenceId) {
}
