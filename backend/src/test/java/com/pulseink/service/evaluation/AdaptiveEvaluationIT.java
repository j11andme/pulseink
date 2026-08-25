package com.pulseink.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.api.AgentEngine;
import com.pulseink.agent.api.AgentExecutionObserver;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.selection.RuleBasedExecutionModeSelector;
import com.pulseink.client.evaluation.FrozenSearchFixtureLoader;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdaptiveEvaluationIT {

    @Test
    void adaptiveUsesTheRealSelectorAndModesShareExternalBudgetsWithoutCripplingTheDag() {
        var seenBudgets = new EnumMap<ExecutionMode, com.pulseink.agent.budget.ExecutionBudget>(
                ExecutionMode.class);
        var seenRequests = new EnumMap<ExecutionMode, AgentExecutionRequest>(ExecutionMode.class);
        var engines = List.of(
                engine(ExecutionMode.DIRECT, seenBudgets, seenRequests),
                engine(ExecutionMode.REACT, seenBudgets, seenRequests),
                engine(ExecutionMode.ORCHESTRATED, seenBudgets, seenRequests));
        var executor = new AgentRuntimeEvaluationPolicyExecutor(
                new RuleBasedExecutionModeSelector(), engines,
                new com.pulseink.agent.model.ModelPolicy(List.of("fake"), java.util.Set.of()),
                new EvaluationScenarioContext(),
                Clock.systemUTC());
        var testCase = TestEvaluationCases.sixSmokeCases().getFirst();
        var orchestratedCase = new EvaluationCase(
                testCase.caseId(), testCase.category(), testCase.smoke(),
                new com.pulseink.domain.execution.TaskProperties(
                        0.9, 3, 3, 3, 0.4, 0.8, 3, 30_000),
                testCase.campaignInput(), testCase.knowledgeSnapshot(),
                testCase.searchFixtures(), testCase.expectedRules(),
                testCase.relevantChunkIds(), testCase.allowedTools(),
                testCase.expectedFinalState(), testCase.rubric(), testCase.failureInjection());

        var adaptive = executor.execute(orchestratedCase, ExecutionPolicy.ADAPTIVE);
        executor.execute(orchestratedCase, ExecutionPolicy.REACT);

        assertThat(adaptive.selectedMode()).isEqualTo(ExecutionMode.ORCHESTRATED);
        assertThat(seenBudgets.get(ExecutionMode.ORCHESTRATED).maxModelCalls())
                .isGreaterThan(seenBudgets.get(ExecutionMode.REACT).maxModelCalls());
        assertThat(seenBudgets.get(ExecutionMode.ORCHESTRATED).maxReactRounds())
                .isGreaterThan(seenBudgets.get(ExecutionMode.REACT).maxReactRounds());
        assertThat(seenBudgets.get(ExecutionMode.REACT).maxTotalTokens())
                .isEqualTo(seenBudgets.get(ExecutionMode.ORCHESTRATED).maxTotalTokens());
        assertThat(seenBudgets.get(ExecutionMode.REACT).maxToolCalls())
                .isEqualTo(seenBudgets.get(ExecutionMode.ORCHESTRATED).maxToolCalls());
        assertThat(orchestratedCase.relevantChunkIds()).allSatisfy(sourceId ->
                assertThat(seenRequests.get(ExecutionMode.REACT).objective())
                        .doesNotContain(sourceId));
        assertThat(seenRequests.get(ExecutionMode.REACT).profile().allowedArtifactTypes())
                .containsExactly(com.pulseink.agent.artifact.ArtifactType.CONTENT_DRAFT);
    }

    private static AgentEngine engine(
            ExecutionMode mode,
            Map<ExecutionMode, com.pulseink.agent.budget.ExecutionBudget> seenBudgets,
            Map<ExecutionMode, AgentExecutionRequest> seenRequests) {
        return new AgentEngine() {
            @Override public ExecutionMode supportedMode() { return mode; }

            @Override
            public AgentExecutionResult execute(AgentExecutionRequest request,
                                                AgentExecutionObserver observer) {
                seenBudgets.put(mode, request.profile().executionBudget());
                seenRequests.put(mode, request);
                return new AgentExecutionResult(request.runId(), mode, List.of(),
                        BudgetSnapshot.ZERO, AgentTerminalReason.SUCCEEDED,
                        new AgentExecutionResult.Metrics(1, 0, 30, 1));
            }
        };
    }
}
