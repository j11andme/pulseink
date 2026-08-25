package com.pulseink.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.execution.TaskProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EvaluationScorerTest {

    private final EvaluationScorer scorer = new EvaluationScorer();

    @Test
    void hardRuleFailureCannotBeOverriddenByHighJudgeScore() {
        var score = scorer.score(citationCase(), execution(List.of()),
                JudgeScore.success(0.99, 0.98, "judge-model", "judge-v1", "content-v1"));

        assertThat(score.hardRulesPassed()).isFalse();
        assertThat(score.passed()).isFalse();
        assertThat(score.violations()).contains("citation_required");
        assertThat(score.quality()).isGreaterThan(0.9);
    }

    @Test
    void computesStandardRetrievalMetricsFromFrozenRanking() {
        var score = scorer.score(citationCase(), execution(List.of("chunk-a")),
                JudgeScore.notRun("content-v1"));

        assertThat(score.recallAtK()).isEqualTo(0.5);
        assertThat(score.precisionAtK()).isEqualTo(0.5);
        assertThat(score.mrr()).isEqualTo(1.0);
        assertThat(score.ndcg()).isBetween(0.6, 0.7);
        assertThat(score.groundedness()).isEqualTo(1.0);
    }

    @Test
    void penalizesCoordinationWithoutQualityGain() {
        var react = scorer.score(citationCase(), execution(List.of("chunk-a")),
                JudgeScore.success(0.82, 0.82, "judge", "judge-v1", "content-v1"));
        var orchestrated = react.withEfficiency(12_000, 13_000, 8);

        var comparison = scorer.compare(react, orchestrated,
                ExecutionPolicy.REACT, ExecutionPolicy.ORCHESTRATED);

        assertThat(comparison.qualityDelta()).isZero();
        assertThat(comparison.coordinationOverhead()).isGreaterThan(1.0);
        assertThat(comparison.preferredPolicy()).isEqualTo(ExecutionPolicy.REACT);
    }

    @Test
    void unknownExpectedRuleFailsClosedInsteadOfBeingSilentlyIgnored() {
        var base = citationCase();
        var testCase = new EvaluationCase(
                base.caseId(), base.category(), base.smoke(), base.taskProperties(),
                base.campaignInput(), base.knowledgeSnapshot(), base.searchFixtures(),
                List.of("future_rule"), base.relevantChunkIds(), base.allowedTools(),
                base.expectedFinalState(), base.rubric(), base.failureInjection());

        var score = scorer.score(testCase, execution(List.of("chunk-a")),
                JudgeScore.notRun("content-v1"));

        assertThat(score.passed()).isFalse();
        assertThat(score.violations()).contains("unsupported_rule:future_rule");
    }

    @Test
    void matchedSystemBudgetIsAHardRule() {
        var overBudget = new EvaluationExecution(
                "case-01", ExecutionPolicy.REACT, ExecutionMode.REACT,
                "WAITING_APPROVAL", AgentTerminalReason.SUCCEEDED,
                List.of("chunk-a"), List.of("chunk-a"),
                Set.of("builtin.knowledge_search"),
                EvaluationBudgetLimits.REACT_MODEL_CALLS + 1,
                EvaluationBudgetLimits.TOOL_CALLS + 1,
                EvaluationBudgetLimits.TOTAL_TOKENS + 1,
                EvaluationBudgetLimits.REACT_ROUNDS + 1,
                0, 1, 100);

        var score = scorer.score(citationCase(), overBudget,
                JudgeScore.notRun("content-v1"));

        assertThat(score.passed()).isFalse();
        assertThat(score.violations()).contains(
                "budget_model_calls", "budget_tool_calls",
                "budget_total_tokens", "budget_react_rounds");
    }

    @Test
    void providerFailureIsReportedAsErrorAndNotAsAgentZero() {
        var failed = new EvaluationExecution(
                "case-01", ExecutionPolicy.REACT, ExecutionMode.REACT,
                "FAILED", AgentTerminalReason.MODEL_FAILURE,
                List.of(), List.of(), Set.of(), 1, 0, 0,
                0, 0, 0, 100, "", List.of(),
                List.of(new EvaluationTraceStep(1, java.time.Instant.EPOCH,
                        "RUNTIME", "runtime", "MODEL_FAILURE", "FAILED",
                        "MODEL_TIMEOUT", List.of())));

        var score = scorer.score(citationCase(), failed, JudgeScore.notRun("content-v1"));

        assertThat(score.status()).isEqualTo(EvaluationSampleStatus.ERROR);
        assertThat(score.failure().stage()).isEqualTo(EvaluationFailureStage.MODEL_PROVIDER);
        assertThat(score.failure().evidence()).contains("summary=MODEL_TIMEOUT");
    }

    @Test
    void duplicateToolRuleUsesExactNameAndArgumentsRatherThanToolNameCount() {
        var calls = List.of(
                new EvaluationToolCall(1, "builtin.knowledge_search",
                        Map.of("query", "alpha"), "SUCCEEDED", List.of("chunk-a")),
                new EvaluationToolCall(2, "builtin.knowledge_search",
                        Map.of("query", "beta"), "SUCCEEDED", List.of("chunk-b")));
        var execution = new EvaluationExecution(
                "case-01", ExecutionPolicy.REACT, ExecutionMode.REACT,
                "WAITING_APPROVAL", AgentTerminalReason.SUCCEEDED,
                List.of("chunk-a", "chunk-b"), List.of("chunk-a"),
                Set.of("builtin.knowledge_search"), 3, 2, 1_000,
                3, 0, 1, 100, "draft", calls, List.of());
        var base = citationCase();
        var noDuplicateCase = new EvaluationCase(
                base.caseId(), base.category(), base.smoke(), base.taskProperties(),
                base.campaignInput(), base.knowledgeSnapshot(), base.searchFixtures(),
                List.of("citation_required", "approval_required", "no_duplicate_tools"),
                base.relevantChunkIds(), base.allowedTools(), base.expectedFinalState(),
                base.rubric(), base.failureInjection());

        assertThat(scorer.score(noDuplicateCase, execution, JudgeScore.notRun("content-v1"))
                .violations()).doesNotContain("no_duplicate_tools");
    }

    private static EvaluationCase citationCase() {
        return new EvaluationCase(
                "case-01", "KNOWLEDGE", true,
                new TaskProperties(0.4, 1, 2, 1, 0.6, 0.8, 2, 20_000),
                new EvaluationCase.CampaignInput("name", "goal", "audience",
                        List.of(CampaignChannel.BLOG), List.of("cite sources")),
                "fixtures/knowledge/brand-a-v1.json",
                "fixtures/search/campaign-a.json",
                List.of("citation_required", "approval_required"),
                List.of("chunk-a", "chunk-b"),
                Set.of("builtin.knowledge_search"),
                "WAITING_APPROVAL", "rubrics/content-v1.json", List.of());
    }

    private static EvaluationExecution execution(List<String> sourceRefs) {
        return new EvaluationExecution(
                "case-01", ExecutionPolicy.REACT, ExecutionMode.REACT,
                "WAITING_APPROVAL", AgentTerminalReason.SUCCEEDED,
                List.of("chunk-a", "noise"), sourceRefs,
                Set.of("builtin.knowledge_search"), 2, 1, 1_000,
                2, 0, 1, 100);
    }
}
