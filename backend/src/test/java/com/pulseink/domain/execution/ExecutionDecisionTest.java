package com.pulseink.domain.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionDecisionTest {

    @Test
    void decisionKeepsAnImmutableSelectionSnapshot() {
        var reasons = new ArrayList<>(List.of("SEQUENTIAL_TASK"));
        var features = new HashMap<String, Object>(Map.of("sequentialDependency", 0.9));

        var decision = new ExecutionDecision(
                ExecutionMode.REACT,
                "selector-v1",
                reasons,
                features,
                8_000L);
        reasons.clear();
        features.clear();

        assertThat(decision.reasonCodes()).containsExactly("SEQUENTIAL_TASK");
        assertThat(decision.featureSnapshot())
                .containsEntry("sequentialDependency", 0.9);
    }

    @Test
    void decisionRejectsAnInvalidBudget() {
        assertThatThrownBy(() -> new ExecutionDecision(
                        ExecutionMode.DIRECT,
                        "selector-v1",
                        List.of("LOW_RISK"),
                        Map.of(),
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("estimated token budget must be positive");
    }

    @Test
    void taskPropertiesRejectProbabilitiesOutsideTheUnitInterval() {
        assertThatThrownBy(() -> new TaskProperties(
                        1.1,
                        1,
                        0,
                        0,
                        0.5,
                        0.5,
                        0,
                        1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("decomposability must be between 0 and 1");
    }
}
