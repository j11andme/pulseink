package com.pulseink.agent.budget;

/**
 * Immutable snapshot of budget usage at a point in time. Used to restore a {@link BudgetTracker}
 * after a restart without lowering already-consumed amounts.
 */
public record BudgetSnapshot(
        int modelCallsUsed,
        int toolCallsUsed,
        long tokensUsed,
        int reactRoundsUsed) {

    public static final BudgetSnapshot ZERO = new BudgetSnapshot(0, 0, 0L, 0);
}
