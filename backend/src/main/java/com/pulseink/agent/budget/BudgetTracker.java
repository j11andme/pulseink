package com.pulseink.agent.budget;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Java-only budget enforcer. All checks happen before external calls; recording happens after.
 * A restored snapshot can never lower already-used amounts. The clock is injectable so tests
 * can deterministically advance past a deadline without sleeping.
 */
public final class BudgetTracker {

    private final ExecutionBudget budget;
    private final Clock clock;
    private int modelCallsUsed;
    private int toolCallsUsed;
    private long tokensUsed;
    private int reactRoundsUsed;

    public BudgetTracker(ExecutionBudget budget) {
        this(budget, Clock.systemUTC(), BudgetSnapshot.ZERO);
    }

    public BudgetTracker(ExecutionBudget budget, Clock clock) {
        this(budget, clock, BudgetSnapshot.ZERO);
    }

    public BudgetTracker(ExecutionBudget budget, BudgetSnapshot restored) {
        this(budget, Clock.systemUTC(), restored);
    }

    public BudgetTracker(ExecutionBudget budget, Clock clock, BudgetSnapshot restored) {
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(restored, "restored snapshot must not be null");
        this.clock = clock;
        this.modelCallsUsed = restored.modelCallsUsed();
        this.toolCallsUsed = restored.toolCallsUsed();
        this.tokensUsed = restored.tokensUsed();
        this.reactRoundsUsed = restored.reactRoundsUsed();
    }

    public void checkModelCall(long estimatedTokens) {
        if (modelCallsUsed >= budget.maxModelCalls()) {
            throw new BudgetExceededException(ExhaustionReason.MODEL_CALL_LIMIT);
        }
        if (tokensUsed + estimatedTokens > budget.maxTotalTokens()) {
            throw new BudgetExceededException(ExhaustionReason.TOKEN_LIMIT);
        }
        checkDeadline();
    }

    public void checkToolCall() {
        if (toolCallsUsed >= budget.maxToolCalls()) {
            throw new BudgetExceededException(ExhaustionReason.TOOL_CALL_LIMIT);
        }
        checkDeadline();
    }

    public void checkReactRound() {
        if (reactRoundsUsed >= budget.maxReactRounds()) {
            throw new BudgetExceededException(ExhaustionReason.REACT_ROUND_LIMIT);
        }
        checkDeadline();
    }

    public void checkDeadline() {
        if (!clock.instant().isBefore(budget.deadline())) {
            throw new BudgetExceededException(ExhaustionReason.DEADLINE_EXCEEDED);
        }
    }

    public void recordModelCall(int inputTokens, int outputTokens) {
        modelCallsUsed++;
        tokensUsed += inputTokens + outputTokens;
    }

    public void recordToolCall() {
        toolCallsUsed++;
    }

    public void recordReactRound() {
        reactRoundsUsed++;
    }

    public BudgetSnapshot snapshot() {
        return new BudgetSnapshot(modelCallsUsed, toolCallsUsed, tokensUsed, reactRoundsUsed);
    }

    public ExecutionBudget budget() { return budget; }
    public int modelCallsUsed() { return modelCallsUsed; }
    public int toolCallsUsed() { return toolCallsUsed; }
    public long tokensUsed() { return tokensUsed; }
    public int reactRoundsUsed() { return reactRoundsUsed; }

    void advanceClockTo(Instant instant) {
        if (clock instanceof MutableClock mutable) {
            mutable.advanceTo(instant);
        } else {
            throw new IllegalStateException("clock is not mutable");
        }
    }

    public enum ExhaustionReason {
        MODEL_CALL_LIMIT,
        TOOL_CALL_LIMIT,
        TOKEN_LIMIT,
        REACT_ROUND_LIMIT,
        DEADLINE_EXCEEDED
    }

    public static final class BudgetExceededException extends RuntimeException {
        private final ExhaustionReason reason;

        public BudgetExceededException(ExhaustionReason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public ExhaustionReason reason() {
            return reason;
        }
    }

    /**
     * Mutable clock used by tests and engines to advance past a deadline deterministically.
     */
    public static final class MutableClock extends Clock {
        private Instant instant;

        public MutableClock(Instant instant) {
            this.instant = Objects.requireNonNull(instant, "instant must not be null");
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        public void advanceTo(Instant newInstant) {
            this.instant = newInstant;
        }
    }
}
