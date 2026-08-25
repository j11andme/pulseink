package com.pulseink.agent.budget;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable execution budget. All positive limits and the deadline are validated at construction.
 * {@code invalidOutputRetries} is fixed to 1 so the repair protocol is deterministic.
 */
public record ExecutionBudget(
        int maxModelCalls,
        int maxToolCalls,
        long maxTotalTokens,
        int maxReactRounds,
        int invalidOutputRetries,
        Instant deadline) {

    public ExecutionBudget {
        Objects.requireNonNull(deadline, "deadline must not be null");
        if (maxModelCalls <= 0) {
            throw new IllegalArgumentException("maxModelCalls must be positive");
        }
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("maxToolCalls must not be negative");
        }
        if (maxTotalTokens <= 0) {
            throw new IllegalArgumentException("maxTotalTokens must be positive");
        }
        if (maxReactRounds <= 0) {
            throw new IllegalArgumentException("maxReactRounds must be positive");
        }
        if (invalidOutputRetries != 1) {
            throw new IllegalArgumentException("invalidOutputRetries must be exactly 1");
        }
        if (deadline.isBefore(Instant.now())) {
            throw new IllegalArgumentException("deadline must not be in the past");
        }
    }

    public static ExecutionBudget defaultReact(Instant deadline) {
        return new ExecutionBudget(10, 10, 64_000L, 4, 1, deadline);
    }

    public static ExecutionBudget defaultDirect(Instant deadline) {
        return new ExecutionBudget(1, 0, 8_000L, 1, 1, deadline);
    }
}
