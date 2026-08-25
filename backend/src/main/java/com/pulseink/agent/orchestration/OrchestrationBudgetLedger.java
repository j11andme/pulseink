package com.pulseink.agent.orchestration;

import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.ExecutionBudget;
import java.util.Objects;

/**
 * Atomic budget ledger for orchestration. Reservations are taken before scheduling a stage and
 * settled with real metrics afterwards; {@code used + reserved} can never exceed the root
 * budget limits.
 */
public final class OrchestrationBudgetLedger {

    private final ExecutionBudget root;
    private int usedModelCalls;
    private int usedToolCalls;
    private long usedTokens;
    private int usedRounds;
    private int reservedModelCalls;
    private int reservedToolCalls;
    private long reservedTokens;
    private int reservedRounds;

    public OrchestrationBudgetLedger(ExecutionBudget root) {
        this(root, BudgetSnapshot.ZERO);
    }

    public OrchestrationBudgetLedger(ExecutionBudget root, BudgetSnapshot restored) {
        this.root = Objects.requireNonNull(root, "root budget must not be null");
        Objects.requireNonNull(restored, "restored snapshot must not be null");
        this.usedModelCalls = restored.modelCallsUsed();
        this.usedToolCalls = restored.toolCallsUsed();
        this.usedTokens = restored.tokensUsed();
        this.usedRounds = restored.reactRoundsUsed();
        if (usedModelCalls < 0 || usedToolCalls < 0 || usedTokens < 0 || usedRounds < 0
                || usedModelCalls > root.maxModelCalls()
                || usedToolCalls > root.maxToolCalls()
                || usedTokens > root.maxTotalTokens()
                || usedRounds > root.maxReactRounds()) {
            throw new IllegalArgumentException(
                    "restored budget snapshot exceeds orchestration root budget");
        }
    }

    public synchronized Reservation reserve(ExecutionBudget taskBudget) {
        Objects.requireNonNull(taskBudget, "taskBudget must not be null");
        if (usedModelCalls + reservedModelCalls + taskBudget.maxModelCalls()
                > root.maxModelCalls()
                || usedToolCalls + reservedToolCalls + taskBudget.maxToolCalls()
                > root.maxToolCalls()
                || usedTokens + reservedTokens + taskBudget.maxTotalTokens()
                > root.maxTotalTokens()
                || usedRounds + reservedRounds + taskBudget.maxReactRounds()
                > root.maxReactRounds()) {
            throw new IllegalStateException(
                    "orchestration budget oversubscribed by task reservation");
        }
        reservedModelCalls += taskBudget.maxModelCalls();
        reservedToolCalls += taskBudget.maxToolCalls();
        reservedTokens += taskBudget.maxTotalTokens();
        reservedRounds += taskBudget.maxReactRounds();
        return new Reservation(
                taskBudget.maxModelCalls(),
                taskBudget.maxToolCalls(),
                taskBudget.maxTotalTokens(),
                taskBudget.maxReactRounds());
    }

    public synchronized void settle(Reservation reservation,
                                     AgentExecutionResult.Metrics metrics) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        Objects.requireNonNull(metrics, "metrics must not be null");
        if (metrics.modelCalls() > reservation.modelCalls()
                || metrics.toolCalls() > reservation.toolCalls()
                || metrics.totalTokens() > reservation.tokens()
                || metrics.reactRounds() > reservation.rounds()) {
            throw new IllegalStateException(
                    "task usage exceeds its reservation");
        }
        reservedModelCalls -= reservation.modelCalls();
        reservedToolCalls -= reservation.toolCalls();
        reservedTokens -= reservation.tokens();
        reservedRounds -= reservation.rounds();
        usedModelCalls += metrics.modelCalls();
        usedToolCalls += metrics.toolCalls();
        usedTokens += metrics.totalTokens();
        usedRounds += metrics.reactRounds();
    }

    public void release(Reservation reservation) {
        settle(reservation, new AgentExecutionResult.Metrics(0, 0, 0, 0));
    }

    public synchronized BudgetSnapshot snapshot() {
        return new BudgetSnapshot(usedModelCalls, usedToolCalls, usedTokens, usedRounds);
    }

    public synchronized int availableModelCalls() {
        return root.maxModelCalls() - usedModelCalls - reservedModelCalls;
    }

    public synchronized int availableToolCalls() {
        return root.maxToolCalls() - usedToolCalls - reservedToolCalls;
    }

    public synchronized long availableTokens() {
        return root.maxTotalTokens() - usedTokens - reservedTokens;
    }

    public record Reservation(
            int modelCalls,
            int toolCalls,
            long tokens,
            int rounds) {
    }
}
