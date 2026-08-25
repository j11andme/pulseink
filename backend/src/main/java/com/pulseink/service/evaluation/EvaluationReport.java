package com.pulseink.service.evaluation;

import com.pulseink.domain.execution.ExecutionMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvaluationReport(
        String reportId,
        Instant generatedAt,
        EvaluationSuite suite,
        int repetitions,
        List<EvaluationRunResult> executions,
        List<PolicySummary> summaries,
        List<PolicyAblationComparison> comparisons,
        Map<ExecutionMode, Integer> selectedModeDistribution,
        int scoredSamples,
        int errorSamples,
        int judgeUnscoredSamples,
        double invalidSampleRate,
        boolean claimsStatisticalSignificance,
        EvaluationRuntimeDescriptor runtime,
        String replayedFromReportId,
        String datasetVersion,
        String scorerVersion,
        CustomEvaluationCase customCase) {

    public EvaluationReport {
        executions = List.copyOf(executions);
        summaries = List.copyOf(summaries);
        comparisons = List.copyOf(comparisons);
        selectedModeDistribution = Map.copyOf(selectedModeDistribution);
        replayedFromReportId = replayedFromReportId == null ? "" : replayedFromReportId;
    }
}
