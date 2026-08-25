package com.pulseink.agent.memory;

import java.util.List;

/**
 * Assembled role context. Never carries prompts, tokens, keys, vectors, hidden reasoning or
 * unapproved candidates.
 */
public record AgentContext(
        String renderedText,
        List<ContextSource> sources,
        boolean workingMemoryCacheHit,
        boolean truncated) {

    public AgentContext {
        sources = List.copyOf(sources);
    }
}
