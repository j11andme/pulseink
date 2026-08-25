package com.pulseink.agent.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BudgetTrackerTest {

    private ExecutionBudget defaultBudget() {
        return new ExecutionBudget(
                5, 10, 10_000L, 4, 1, Instant.now().plus(Duration.ofMinutes(30)));
    }

    // ---- budget construction validation ----

    @Test
    void rejectsZeroOrNegativeLimits() {
        var future = Instant.now().plus(Duration.ofMinutes(30));
        assertThatThrownBy(() -> new ExecutionBudget(0, 10, 10_000L, 4, 1, future))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionBudget(5, -1, 10_000L, 4, 1, future))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionBudget(5, 10, 0L, 4, 1, future))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionBudget(5, 10, 10_000L, 0, 1, future))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPastDeadline() {
        assertThatThrownBy(() -> new ExecutionBudget(
                5, 10, 10_000L, 4, 1, Instant.now().minus(Duration.ofSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullDeadline() {
        assertThatThrownBy(() -> new ExecutionBudget(
                5, 10, 10_000L, 4, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void invalidOutputRetriesMustBeExactlyOne() {
        var future = Instant.now().plus(Duration.ofMinutes(30));
        assertThatThrownBy(() -> new ExecutionBudget(5, 10, 10_000L, 4, 0, future))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionBudget(5, 10, 10_000L, 4, 2, future))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- model call budget ----

    @Test
    void modelCallLimitExceededBeforeNextCall() {
        var budget = new ExecutionBudget(2, 10, 100_000L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var tracker = new BudgetTracker(budget, fixedClock(Instant.now()));

        tracker.checkModelCall(100);
        tracker.recordModelCall(50, 50);

        tracker.checkModelCall(100);
        tracker.recordModelCall(50, 50);

        assertThatThrownBy(() -> tracker.checkModelCall(100))
                .isInstanceOf(BudgetTracker.BudgetExceededException.class);
        assertThat(tracker.modelCallsUsed()).isEqualTo(2);
    }

    // ---- tool call budget ----

    @Test
    void toolCallLimitExceededBeforeNextCall() {
        var budget = new ExecutionBudget(5, 2, 100_000L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var tracker = new BudgetTracker(budget, fixedClock(Instant.now()));

        tracker.checkToolCall();
        tracker.recordToolCall();
        tracker.checkToolCall();
        tracker.recordToolCall();

        assertThatThrownBy(() -> tracker.checkToolCall())
                .isInstanceOf(BudgetTracker.BudgetExceededException.class);
        assertThat(tracker.toolCallsUsed()).isEqualTo(2);
    }

    // ---- token budget ----

    @Test
    void tokenLimitExceededBeforeNextCall() {
        var budget = new ExecutionBudget(5, 10, 200L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var tracker = new BudgetTracker(budget, fixedClock(Instant.now()));

        tracker.checkModelCall(150);
        tracker.recordModelCall(100, 50);

        assertThatThrownBy(() -> tracker.checkModelCall(100))
                .isInstanceOf(BudgetTracker.BudgetExceededException.class);
        assertThat(tracker.tokensUsed()).isEqualTo(150);
    }

    // ---- react round budget ----

    @Test
    void reactRoundLimitExceededOnFifthRound() {
        var budget = new ExecutionBudget(10, 10, 100_000L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var tracker = new BudgetTracker(budget, fixedClock(Instant.now()));

        for (int i = 0; i < 4; i++) {
            tracker.checkReactRound();
            tracker.recordReactRound();
        }

        assertThatThrownBy(() -> tracker.checkReactRound())
                .isInstanceOf(BudgetTracker.BudgetExceededException.class);
        assertThat(tracker.reactRoundsUsed()).isEqualTo(4);
    }

    // ---- deadline ----

    @Test
    void deadlineExceededBeforeExternalCall() {
        var now = Instant.now();
        var budget = new ExecutionBudget(5, 10, 100_000L, 4, 1,
                now.plus(Duration.ofSeconds(30)));
        var tracker = new BudgetTracker(budget, fixedClock(now));

        tracker.checkModelCall(100);
        tracker.recordModelCall(10, 10);

        tracker.advanceClockTo(now.plus(Duration.ofSeconds(31)));

        assertThatThrownBy(() -> tracker.checkModelCall(100))
                .isInstanceOf(BudgetTracker.BudgetExceededException.class);
        assertThatThrownBy(() -> tracker.checkToolCall())
                .isInstanceOf(BudgetTracker.BudgetExceededException.class);
        assertThatThrownBy(() -> tracker.checkReactRound())
                .isInstanceOf(BudgetTracker.BudgetExceededException.class);

        assertThat(tracker.modelCallsUsed()).isEqualTo(1);
        assertThat(tracker.toolCallsUsed()).isEqualTo(0);
        assertThat(tracker.reactRoundsUsed()).isEqualTo(0);
    }

    // ---- snapshot restore ----

    @Test
    void snapshotRestorePreservesUsedAmounts() {
        var budget = defaultBudget();
        var tracker = new BudgetTracker(budget, fixedClock(Instant.now()));
        tracker.recordModelCall(100, 200);
        tracker.recordToolCall();
        tracker.recordReactRound();

        var snapshot = tracker.snapshot();
        assertThat(snapshot.modelCallsUsed()).isEqualTo(1);
        assertThat(snapshot.toolCallsUsed()).isEqualTo(1);
        assertThat(snapshot.tokensUsed()).isEqualTo(300);
        assertThat(snapshot.reactRoundsUsed()).isEqualTo(1);

        var restored = new BudgetTracker(budget, fixedClock(Instant.now()), snapshot);
        assertThat(restored.modelCallsUsed()).isEqualTo(1);
        assertThat(restored.toolCallsUsed()).isEqualTo(1);
        assertThat(restored.tokensUsed()).isEqualTo(300);
        assertThat(restored.reactRoundsUsed()).isEqualTo(1);
    }

    @Test
    void restoredTrackerDoesNotLowerUsedAmounts() {
        var budget = new ExecutionBudget(3, 5, 1000L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var snapshot = new BudgetSnapshot(2, 3, 500, 3);
        var tracker = new BudgetTracker(budget, fixedClock(Instant.now()), snapshot);

        assertThat(tracker.modelCallsUsed()).isEqualTo(2);
        tracker.checkModelCall(100);
        tracker.recordModelCall(50, 50);
        assertThat(tracker.modelCallsUsed()).isEqualTo(3);
    }

    // ---- exhaustion reason is typed ----

    @Test
    void exhaustionReasonIsTypedEnum() {
        var budget = new ExecutionBudget(1, 10, 100_000L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var tracker = new BudgetTracker(budget, fixedClock(Instant.now()));

        tracker.checkModelCall(100);
        tracker.recordModelCall(10, 10);

        var thrown = catchThrowable(() -> tracker.checkModelCall(100));
        assertThat(thrown).isInstanceOf(BudgetTracker.BudgetExceededException.class);
        var ex = (BudgetTracker.BudgetExceededException) thrown;
        assertThat(ex.reason()).isEqualTo(BudgetTracker.ExhaustionReason.MODEL_CALL_LIMIT);
    }

    @Test
    void tokenExhaustionReasonIsTyped() {
        var budget = new ExecutionBudget(5, 10, 100L, 4, 1,
                Instant.now().plus(Duration.ofMinutes(30)));
        var tracker = new BudgetTracker(budget, fixedClock(Instant.now()));

        tracker.checkModelCall(50);
        tracker.recordModelCall(40, 20);

        var thrown = catchThrowable(() -> tracker.checkModelCall(50));
        assertThat(thrown).isInstanceOf(BudgetTracker.BudgetExceededException.class);
        assertThat(((BudgetTracker.BudgetExceededException) thrown).reason())
                .isEqualTo(BudgetTracker.ExhaustionReason.TOKEN_LIMIT);
    }

    @Test
    void deadlineExhaustionReasonIsTyped() {
        var now = Instant.now();
        var budget = new ExecutionBudget(5, 10, 100_000L, 4, 1,
                now.plus(Duration.ofSeconds(10)));
        var tracker = new BudgetTracker(budget, fixedClock(now));
        tracker.advanceClockTo(now.plus(Duration.ofSeconds(11)));

        var thrown = catchThrowable(() -> tracker.checkModelCall(100));
        assertThat(thrown).isInstanceOf(BudgetTracker.BudgetExceededException.class);
        assertThat(((BudgetTracker.BudgetExceededException) thrown).reason())
                .isEqualTo(BudgetTracker.ExhaustionReason.DEADLINE_EXCEEDED);
    }

    // ---- helper ----

    private static Clock fixedClock(Instant instant) {
        return new BudgetTracker.MutableClock(instant);
    }
}
