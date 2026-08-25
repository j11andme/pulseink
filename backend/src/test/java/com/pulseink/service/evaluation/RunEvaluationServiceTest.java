package com.pulseink.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunEvaluationServiceTest {

    @Test
    void smokeSuiteRunsSixCasesAcrossFourPoliciesExactlyOnce() {
        var cases = TestEvaluationCases.sixSmokeCases();
        EvaluationPolicyExecutor executor = (testCase, policy) -> new EvaluationExecution(
                testCase.caseId(), policy,
                policy == ExecutionPolicy.ADAPTIVE ? ExecutionMode.ORCHESTRATED
                        : ExecutionMode.valueOf(policy.name()),
                "WAITING_APPROVAL", AgentTerminalReason.SUCCEEDED,
                testCase.relevantChunkIds(), testCase.relevantChunkIds(), Set.of(),
                1, 0, 100, 1, 0, 0, 10);
        var service = new RunEvaluationService(
                () -> cases,
                executor,
                new EvaluationScorer(),
                (testCase, left, right) -> JudgeScore.notRun("content-v1"),
                report -> Path.of("report.json"));

        var report = service.run(new EvaluationRequest(
                EvaluationSuite.SMOKE,
                List.of(ExecutionPolicy.DIRECT, ExecutionPolicy.REACT,
                        ExecutionPolicy.ORCHESTRATED, ExecutionPolicy.ADAPTIVE),
                false));

        assertThat(report.executions()).hasSize(24);
        assertThat(report.summaries()).hasSize(4);
        assertThat(report.executions()).filteredOn(result ->
                result.policy() == ExecutionPolicy.ADAPTIVE)
                .allMatch(result -> result.selectedMode() == ExecutionMode.ORCHESTRATED);
        assertThat(report.claimsStatisticalSignificance()).isFalse();
    }

    @Test
    void deterministicFailureSkipsSemanticJudge() {
        var testCase = TestEvaluationCases.sixSmokeCases().getFirst();
        var citationCase = new EvaluationCase(
                testCase.caseId(), testCase.category(), true, testCase.taskProperties(),
                testCase.campaignInput(), testCase.knowledgeSnapshot(), testCase.searchFixtures(),
                List.of("citation_required"), List.of("chunk-a"), testCase.allowedTools(),
                testCase.expectedFinalState(), testCase.rubric(), List.of());
        EvaluationPolicyExecutor executor = (ignored, policy) -> new EvaluationExecution(
                citationCase.caseId(), policy,
                policy == ExecutionPolicy.REACT ? ExecutionMode.REACT : ExecutionMode.ORCHESTRATED,
                "WAITING_APPROVAL", AgentTerminalReason.SUCCEEDED,
                List.of("chunk-a"), List.of(), Set.of(), 1, 0, 100, 1, 0, 0, 10);
        var judgeCalls = new AtomicInteger();
        var service = new RunEvaluationService(
                () -> List.of(citationCase), executor, new EvaluationScorer(),
                (ignored, left, right) -> {
                    judgeCalls.incrementAndGet();
                    return JudgeScore.success(1, 1, "judge", "judge-v1", "content-v1");
                }, report -> Path.of("report.json"));

        var report = service.run(new EvaluationRequest(
                EvaluationSuite.SMOKE,
                List.of(ExecutionPolicy.REACT, ExecutionPolicy.ORCHESTRATED), true));

        assertThat(judgeCalls).hasValue(0);
        assertThat(report.executions()).allSatisfy(result -> {
            assertThat(result.score().hardRulesPassed()).isFalse();
            assertThat(result.judge().executed()).isFalse();
        });
    }

    @Test
    void stabilityKeepsThreeIndependentRepetitionsAndPairwiseComparisons() {
        var cases = TestEvaluationCases.sixSmokeCases();
        EvaluationPolicyExecutor executor = (testCase, policy) -> new EvaluationExecution(
                testCase.caseId(), policy,
                policy == ExecutionPolicy.ADAPTIVE ? ExecutionMode.ORCHESTRATED
                        : ExecutionMode.valueOf(policy.name()),
                "WAITING_APPROVAL", AgentTerminalReason.SUCCEEDED,
                testCase.relevantChunkIds(), testCase.relevantChunkIds(), Set.of(),
                policy == ExecutionPolicy.ORCHESTRATED ? 3 : 1, 0,
                policy == ExecutionPolicy.ORCHESTRATED ? 300 : 100,
                1, 0, policy == ExecutionPolicy.ORCHESTRATED ? 3 : 0, 10);
        var service = new RunEvaluationService(
                () -> cases, executor, new EvaluationScorer(),
                (testCase, left, right) -> JudgeScore.notRun("content-v1"),
                report -> Path.of("report.json"));

        var report = service.run(new EvaluationRequest(
                EvaluationSuite.STABILITY,
                List.of(ExecutionPolicy.DIRECT, ExecutionPolicy.REACT,
                        ExecutionPolicy.ORCHESTRATED, ExecutionPolicy.ADAPTIVE), false));

        assertThat(report.executions()).hasSize(72);
        assertThat(report.executions()).extracting(EvaluationRunResult::repetition)
                .containsOnly(1, 2, 3);
        assertThat(report.comparisons()).hasSize(18)
                .allSatisfy(comparison -> {
                    assertThat(comparison.comparable()).isFalse();
                    assertThat(comparison.reason()).isEqualTo("judge_unscored");
                });
        assertThat(report.summaries()).allSatisfy(summary -> {
            assertThat(summary.qualityStdDev()).isNotNegative();
            assertThat(summary.latencyStdDev()).isNotNegative();
        });
    }

    @Test
    void customCaseScoresEachSelectedPolicyAgainstTheUserReference() {
        var judgeCalls = new AtomicInteger();
        EvaluationPolicyExecutor executor = (testCase, policy) -> new EvaluationExecution(
                testCase.caseId(), policy, ExecutionMode.valueOf(policy.name()),
                "WAITING_APPROVAL", AgentTerminalReason.SUCCEEDED,
                List.of(), List.of(), Set.of(), 1, 0, 120, 1, 0, 0, 15,
                "候选内容-" + policy.name());
        var service = new RunEvaluationService(
                () -> TestEvaluationCases.sixSmokeCases(), executor, new EvaluationScorer(),
                (testCase, candidate, reference) -> {
                    judgeCalls.incrementAndGet();
                    assertThat(reference.candidateText()).isEqualTo("理想参考结果");
                    return JudgeScore.success(0.8, 1.0, "judge", "judge-v2", "content-v1",
                            "候选基本符合参考结果");
                }, report -> Path.of("report.json"));

        var report = service.runCustom(new CustomEvaluationRequest(
                "撰写 Java 秋招内容", "理想参考结果", "应届开发者",
                CampaignChannel.SOCIAL, List.of("语气专业"),
                List.of(ExecutionPolicy.DIRECT, ExecutionPolicy.REACT)));

        assertThat(report.suite()).isEqualTo(EvaluationSuite.CUSTOM);
        assertThat(report.datasetVersion()).isEqualTo("user-custom-v1");
        assertThat(report.executions()).hasSize(2)
                .allSatisfy(result -> {
                    assertThat(result.score().qualityScored()).isTrue();
                    assertThat(result.score().quality()).isEqualTo(0.8);
                });
        assertThat(report.customCase()).isNotNull();
        assertThat(report.customCase().task()).isEqualTo("撰写 Java 秋招内容");
        assertThat(report.customCase().expectedResult()).isEqualTo("理想参考结果");
        assertThat(judgeCalls).hasValue(2);
    }

    @Test
    void customCaseRejectsBlankTaskAndExpectedResult() {
        assertThatThrownBy(() -> new CustomEvaluationRequest(
                " ", "理想结果", "通用受众", CampaignChannel.SOCIAL,
                List.of(), List.of(ExecutionPolicy.DIRECT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
        assertThatThrownBy(() -> new CustomEvaluationRequest(
                "任务", " ", "通用受众", CampaignChannel.SOCIAL,
                List.of(), List.of(ExecutionPolicy.DIRECT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedResult");
    }
}
