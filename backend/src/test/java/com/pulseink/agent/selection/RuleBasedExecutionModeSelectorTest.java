package com.pulseink.agent.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.execution.TaskProperties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RuleBasedExecutionModeSelectorTest {

    private final ExecutionModeSelector selector = new RuleBasedExecutionModeSelector();

    @ParameterizedTest
    @MethodSource("adaptiveCases")
    void adaptiveSelectsExpectedMode(TaskProperties properties, ExecutionMode expected) {
        var decision = selector.select(ExecutionPolicy.ADAPTIVE, properties);

        assertThat(decision.selectedMode()).isEqualTo(expected);
    }

    static Stream<Arguments> adaptiveCases() {
        return Stream.of(
                Arguments.of(
                        new TaskProperties(0.1, 1, 0, 0, 0.1, 0.1, 0, 2_000),
                        ExecutionMode.DIRECT),
                Arguments.of(
                        new TaskProperties(0.2, 1, 2, 1, 0.9, 0.3, 3, 8_000),
                        ExecutionMode.REACT),
                Arguments.of(
                        new TaskProperties(0.8, 3, 3, 3, 0.4, 0.8, 5, 20_000),
                        ExecutionMode.ORCHESTRATED));
    }

    @Test
    void everyDecisionCarriesSelectorVersionAndOrderedReasonCodes() {
        var direct = selector.select(ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.1, 1, 0, 0, 0.1, 0.1, 0, 2_000));
        assertThat(direct.selectorPolicyVersion())
                .isEqualTo(RuleBasedExecutionModeSelector.POLICY_VERSION);
        assertThat(direct.reasonCodes()).containsExactly("LOW_RISK_SINGLE_OUTPUT");

        var orchestrated = selector.select(ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.8, 3, 3, 3, 0.4, 0.8, 5, 20_000));
        assertThat(orchestrated.reasonCodes()).containsExactly("DECOMPOSABLE_OR_HIGH_RISK");

        var react = selector.select(ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.2, 1, 2, 1, 0.9, 0.3, 3, 8_000));
        assertThat(react.reasonCodes()).containsExactly("UNIFIED_CONTEXT_PREFERRED");
    }

    @Test
    void manualFixedPolicyMapsDirectlyAndRecordsManualOverride() {
        assertThat(selector.select(ExecutionPolicy.DIRECT, anyProperties()).selectedMode())
                .isEqualTo(ExecutionMode.DIRECT);
        assertThat(selector.select(ExecutionPolicy.REACT, anyProperties()).selectedMode())
                .isEqualTo(ExecutionMode.REACT);
        assertThat(selector.select(ExecutionPolicy.ORCHESTRATED, anyProperties()).selectedMode())
                .isEqualTo(ExecutionMode.ORCHESTRATED);

        assertThat(selector.select(ExecutionPolicy.DIRECT, anyProperties()).reasonCodes())
                .containsExactly("MANUAL_POLICY_OVERRIDE");
    }

    @Test
    void tokenBudgetIsDeterministicAndCappedByPolicy() {
        var decision = selector.select(ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.1, 1, 0, 0, 0.1, 0.1, 0, 2_000));
        assertThat(decision.estimatedTokenBudget()).isEqualTo(2_000L);

        var capped = selector.select(ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.8, 3, 3, 3, 0.4, 0.8, 5, 100_000));
        assertThat(capped.estimatedTokenBudget()).isEqualTo(SelectorPolicy.V1.maxTokenBudget());
    }

    @Test
    void featureSnapshotIsImmutableAndCoversEveryInput() {
        var decision = selector.select(ExecutionPolicy.ADAPTIVE,
                new TaskProperties(0.8, 3, 3, 3, 0.4, 0.8, 5, 20_000));

        assertThatThrownBy(() -> decision.featureSnapshot().put("extra", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(decision.featureSnapshot())
                .containsEntry("channelCount", 3)
                .containsEntry("parallelResearchBranches", 3)
                .containsEntry("factualRisk", 0.8)
                .containsEntry("latencyBudgetMs", 20_000L)
                .hasSize(8);
    }

    @Test
    void nullPolicyOrNullPropertiesAreRejected() {
        assertThatThrownBy(() -> selector.select(null, anyProperties()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> selector.select(ExecutionPolicy.ADAPTIVE, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static TaskProperties anyProperties() {
        return new TaskProperties(0.2, 1, 2, 1, 0.9, 0.3, 3, 8_000);
    }
}
