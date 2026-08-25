package com.pulseink.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.client.evaluation.FileSystemEvaluationCaseCatalog;
import com.pulseink.client.evaluation.FileSystemEvaluationReportWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Re-grades saved real trajectories after scorer changes without any model or tool calls. */
@EnabledIfSystemProperty(named = "pulseink.replay-report", matches = ".+\\.json")
class EvaluationReportReplayIT {

    @Test
    void regradesSavedRealTrajectoriesWithoutExecutingAgents() throws Exception {
        Path sourcePath = Path.of(System.getProperty("pulseink.replay-report"));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var source = mapper.readValue(sourcePath.toFile(), EvaluationReport.class);
        Path evalRoot = Path.of("..", "evals");
        var service = new RunEvaluationService(
                new FileSystemEvaluationCaseCatalog(evalRoot, mapper),
                (testCase, policy) -> {
                    throw new AssertionError("replay must not execute an agent");
                },
                new EvaluationScorer(),
                (testCase, left, right) -> {
                    throw new AssertionError("replay must not call a judge");
                },
                new FileSystemEvaluationReportWriter(evalRoot.resolve("reports"), mapper));

        var replayed = service.regrade(source);

        assertThat(replayed.replayedFromReportId()).isEqualTo(source.reportId());
        assertThat(replayed.executions()).hasSameSizeAs(source.executions());
        assertThat(replayed.runtime()).isEqualTo(source.runtime());
        assertThat(replayed.scorerVersion()).isEqualTo(RunEvaluationService.SCORER_VERSION);
        assertThat(replayed.executions()).noneMatch(result ->
                result.score().failure().code().equals(
                        "PROMPT_INJECTION_SCENARIO_MISSING"));
        assertThat(replayed.executions()).filteredOn(result ->
                        result.caseId().equals("repair-15-tool-timeout"))
                .allMatch(result -> result.judge().status() == EvaluationJudgeStatus.NOT_RUN);
        assertThat(Files.isRegularFile(evalRoot.resolve("reports")
                .resolve(replayed.reportId() + ".json"))).isTrue();
    }
}
