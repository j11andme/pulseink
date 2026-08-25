package com.pulseink.client.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.service.evaluation.EvaluationReport;
import com.pulseink.service.evaluation.EvaluationReportWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FileSystemEvaluationReportWriter implements EvaluationReportWriter {

    private final Path reportRoot;
    private final ObjectMapper mapper;

    public FileSystemEvaluationReportWriter(Path reportRoot, ObjectMapper mapper) {
        this.reportRoot = reportRoot.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override
    public Path write(EvaluationReport report) {
        try {
            Files.createDirectories(reportRoot);
            Path json = reportRoot.resolve(report.reportId() + ".json");
            Path markdown = reportRoot.resolve(report.reportId() + ".md");
            mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
            Files.writeString(markdown, markdown(report), StandardOpenOption.CREATE_NEW);
            return json;
        } catch (IOException ex) {
            throw new IllegalStateException("failed to write evaluation report", ex);
        }
    }

    private static String markdown(EvaluationReport report) {
        var text = new StringBuilder()
                .append("# PulseInk Evaluation Report\n\n")
                .append("- Report: `").append(report.reportId()).append("`\n")
                .append("- Suite: `").append(report.suite()).append("`\n")
                .append("- Dataset: `").append(report.datasetVersion()).append("`\n")
                .append("- Scorer: `").append(report.scorerVersion()).append("`\n")
                .append("- Runtime provider: `").append(report.runtime().provider()).append("`\n")
                .append("- Runtime model: `").append(report.runtime().model()).append("`\n")
                .append(report.replayedFromReportId().isBlank() ? ""
                        : "- Replayed from: `" + report.replayedFromReportId() + "`\n")
                .append("- Simulated runtime: **")
                .append(report.runtime().simulated() ? "Yes" : "No").append("**\n")
                .append("- Scored / Error / Judge unscored: **")
                .append(report.scoredSamples()).append(" / ")
                .append(report.errorSamples()).append(" / ")
                .append(report.judgeUnscoredSamples()).append("**\n")
                .append("- Invalid sample rate: **")
                .append(format(report.invalidSampleRate())).append("**\n")
                .append("- Statistical significance claimed: **No**\n\n")
                .append("## Policy summary\n\n")
                .append("| Policy | Runs | Scored | Errors | Pass rate | Quality n | Judge unscored | Quality | Quality σ | Groundedness | Tokens | Latency ms | Latency σ | Coordination |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (var summary : report.summaries()) {
            text.append("| ").append(summary.policy()).append(" | ")
                    .append(summary.executions()).append(" | ")
                    .append(summary.scoredExecutions()).append(" | ")
                    .append(summary.errors()).append(" | ")
                    .append(format(summary.passRate())).append(" | ")
                    .append(summary.qualitySamples()).append(" | ")
                    .append(summary.judgeUnscored()).append(" | ")
                    .append(format(summary.averageQuality())).append(" | ")
                    .append(format(summary.qualityStdDev())).append(" | ")
                    .append(format(summary.averageGroundedness())).append(" | ")
                    .append(format(summary.averageTokens())).append(" | ")
                    .append(format(summary.averageLatencyMs())).append(" | ")
                    .append(format(summary.latencyStdDev())).append(" | ")
                    .append(format(summary.averageCoordinationArtifacts())).append(" |\n");
        }
        text.append("\n## REACT vs ORCHESTRATED ablation\n\n")
                .append("| Case | Repetition | Quality delta | Coordination overhead | Preferred |\n")
                .append("|---|---:|---:|---:|---|\n");
        for (var comparison : report.comparisons()) {
            text.append("| ").append(comparison.caseId()).append(" | ")
                    .append(comparison.repetition()).append(" | ")
                    .append(comparison.comparable() ? format(comparison.qualityDelta()) : "UNSCORED")
                    .append(" | ")
                    .append(comparison.comparable()
                            ? format(comparison.coordinationOverhead()) + "x" : comparison.reason())
                    .append(" | ")
                    .append(comparison.preferredPolicy() == null ? "—" : comparison.preferredPolicy())
                    .append(" |\n");
        }
        text.append("\n## Judge outcomes\n\n")
                .append("| Case | Rep | Policy | Status | Model | Prompt | Code | Explanation |\n")
                .append("|---|---:|---|---|---|---|---|---|\n");
        for (var result : report.executions()) {
            if (result.policy() != com.pulseink.domain.execution.ExecutionPolicy.REACT
                    && result.judge().status()
                            == com.pulseink.service.evaluation.EvaluationJudgeStatus.NOT_RUN) {
                continue;
            }
            text.append("| ").append(result.caseId()).append(" | ")
                    .append(result.repetition()).append(" | ")
                    .append(result.policy()).append(" | ")
                    .append(result.judge().status()).append(" | ")
                    .append(cell(result.judge().judgeModel())).append(" | ")
                    .append(cell(result.judge().promptVersion())).append(" | ")
                    .append(cell(result.judge().failureCode())).append(" | ")
                    .append(cell(result.judge().explanation())).append(" |\n");
        }
        text.append("\n## Case results\n\n")
                .append("| Case | Rep | Policy | Mode | Sample | Passed | Quality | Grounded | Calls M/T | Tokens | Latency ms | Terminal | Failure stage/code | Evidence |\n")
                .append("|---|---:|---|---|---|---|---:|---:|---:|---:|---:|---|---|---|\n");
        for (var result : report.executions()) {
            text.append("| ").append(result.caseId()).append(" | ")
                    .append(result.repetition()).append(" | ")
                    .append(result.policy()).append(" | ")
                    .append(result.selectedMode()).append(" | ")
                    .append(result.score().status()).append(" | ")
                    .append(result.score().passed()).append(" | ")
                    .append(result.score().qualityScored()
                            ? format(result.score().quality()) : "UNSCORED").append(" | ")
                    .append(format(result.score().groundedness())).append(" | ")
                    .append(result.execution().modelCalls()).append("/")
                    .append(result.execution().toolCalls()).append(" | ")
                    .append(result.execution().totalTokens()).append(" | ")
                    .append(result.execution().latencyMs()).append(" | ")
                    .append(result.execution().terminalReason()).append(" | ")
                    .append(result.score().failure().stage()).append("/")
                    .append(result.score().failure().code()).append(" | ")
                    .append(String.join(", ", result.score().failure().evidence()))
                    .append(" |\n");
        }
        return text.toString();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String cell(String value) {
        if (value == null || value.isBlank()) return "—";
        return value.replace("|", "\\|").replaceAll("[\\r\\n]+", " ");
    }
}
