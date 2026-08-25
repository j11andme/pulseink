package com.pulseink.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.ExecutionBudget;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrchestrationBudgetLedgerTest {

    private ExecutionBudget root() {
        return new ExecutionBudget(10, 10, 1000L, 10, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
    }

    @Test
    void reservationIsBoundedByRootLimits() {
        var ledger = new OrchestrationBudgetLedger(root());
        var taskBudget = new ExecutionBudget(5, 3, 500L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var reservation = ledger.reserve(taskBudget);

        assertThat(reservation.modelCalls()).isEqualTo(5);
        assertThat(ledger.availableModelCalls()).isEqualTo(5);
        assertThat(ledger.availableTokens()).isEqualTo(500L);
    }

    @Test
    void oversubscriptionIsRejected() {
        var ledger = new OrchestrationBudgetLedger(
                new ExecutionBudget(4, 4, 100L, 4, 1,
                        Instant.now().plus(Duration.ofMinutes(30))));
        var taskBudget = new ExecutionBudget(3, 3, 80L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        ledger.reserve(taskBudget);

        assertThatThrownBy(() -> ledger.reserve(taskBudget))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget");
    }

    @Test
    void settleAddsRealUsageAndReleasesReservation() {
        var ledger = new OrchestrationBudgetLedger(root());
        var taskBudget = new ExecutionBudget(5, 3, 500L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var reservation = ledger.reserve(taskBudget);
        ledger.settle(reservation,
                new AgentExecutionResult.Metrics(2, 1, 120, 2));

        var snapshot = ledger.snapshot();
        assertThat(snapshot.modelCallsUsed()).isEqualTo(2);
        assertThat(snapshot.toolCallsUsed()).isEqualTo(1);
        assertThat(snapshot.tokensUsed()).isEqualTo(120);
        assertThat(snapshot.reactRoundsUsed()).isEqualTo(2);
        assertThat(ledger.availableModelCalls()).isEqualTo(8);
    }

    @Test
    void settleAboveReservationFailsClosed() {
        var ledger = new OrchestrationBudgetLedger(root());
        var taskBudget = new ExecutionBudget(2, 2, 100L, 2, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var reservation = ledger.reserve(taskBudget);
        assertThatThrownBy(() -> ledger.settle(reservation,
                new AgentExecutionResult.Metrics(3, 0, 0, 0)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restoredSnapshotCountsTowardsUsed() {
        var restored = new BudgetSnapshot(3, 2, 300, 3);
        var ledger = new OrchestrationBudgetLedger(root(), restored);
        assertThat(ledger.availableModelCalls()).isEqualTo(7);
        assertThat(ledger.availableTokens()).isEqualTo(700L);
    }

    @Test
    void concurrentReservationsAreAtomic() throws Exception {
        var ledger = new OrchestrationBudgetLedger(
                new ExecutionBudget(6, 6, 600L, 6, 1,
                        Instant.now().plus(Duration.ofMinutes(30))));
        var taskBudget = new ExecutionBudget(2, 2, 200L, 2, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        int threads = 4;
        var barrier = new java.util.concurrent.CountDownLatch(threads);
        var done = new java.util.concurrent.CountDownLatch(threads);
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread.ofPlatform().start(() -> {
                try {
                    barrier.countDown();
                    barrier.await();
                    try {
                        ledger.reserve(taskBudget);
                        successes.incrementAndGet();
                    } catch (IllegalStateException oversubscribed) {
                        // expected for some threads
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(successes.get()).isEqualTo(3);
    }
}
