package com.pulseink.controller.run;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.checkpoint.RunCheckpoint;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.execution.TaskProperties;
import com.pulseink.service.campaign.QueryRunUseCase;
import com.pulseink.service.campaign.RunEvent;
import com.pulseink.service.campaign.RunEventType;
import com.pulseink.service.campaign.RunExecutionUseCase;
import com.pulseink.service.campaign.StartRunUseCase;
import com.pulseink.service.campaign.StartRunUseCase.StartRunCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RunController {

    private final StartRunUseCase startRunUseCase;
    private final QueryRunUseCase queryRunUseCase;
    private final RunExecutionUseCase runExecutionUseCase;

    public RunController(StartRunUseCase startRunUseCase,
                         QueryRunUseCase queryRunUseCase,
                         RunExecutionUseCase runExecutionUseCase) {
        this.startRunUseCase = Objects.requireNonNull(startRunUseCase);
        this.queryRunUseCase = Objects.requireNonNull(queryRunUseCase);
        this.runExecutionUseCase = Objects.requireNonNull(runExecutionUseCase);
    }

    @PostMapping("/campaigns/{id}/runs")
    public ResponseEntity<RunResponse> start(
            @PathVariable long id,
            @Valid @RequestBody StartRunRequest request) {
        if (id <= 0) {
            throw new IllegalArgumentException("campaign id must be positive");
        }
        var run = startRunUseCase.start(
                new StartRunCommand(id, request.requestedPolicy(), request.taskProperties()));
        runExecutionUseCase.launch(run.id());
        return ResponseEntity.created(URI.create("/api/runs/" + run.id()))
                .body(toResponse(run));
    }

    @GetMapping("/campaigns/{id}/runs")
    public List<RunResponse> history(@PathVariable long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("campaign id must be positive");
        }
        return queryRunUseCase.history(id).stream()
                .map(RunController::toResponse)
                .toList();
    }

    @GetMapping("/runs/{id}/execution-decision")
    public RunResponse executionDecision(@PathVariable long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("run id must be positive");
        }
        return toResponse(queryRunUseCase.executionDecision(id));
    }

    @GetMapping("/runs/{id}/trace")
    public RunTraceResponse trace(@PathVariable long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("run id must be positive");
        }
        var trace = queryRunUseCase.trace(id);
        return new RunTraceResponse(
                toResponse(trace.run()),
                trace.lastEventSequence(),
                trace.checkpoint() == null ? null : toResponse(trace.checkpoint()),
                trace.events().stream().map(RunController::toResponse).toList());
    }

    private static RunResponse toResponse(CampaignRun run) {
        return new RunResponse(
                run.id(),
                run.campaignId(),
                run.requestedPolicy(),
                run.selectedMode(),
                run.selectorPolicyVersion(),
                run.selectionReasonCodes(),
                run.selectionFeatureSnapshot(),
                run.estimatedTokenBudget(),
                run.state(),
                run.failureReason(),
                run.startedAt(),
                run.completedAt(),
                run.createdAt(),
                run.updatedAt());
    }

    private static CheckpointResponse toResponse(RunCheckpoint checkpoint) {
        return new CheckpointResponse(
                checkpoint.checkpointType(),
                checkpoint.schemaVersion(),
                checkpoint.lastCompletedRound(),
                checkpoint.lastPersistedEventSequence(),
                checkpoint.createdAt(),
                toResponse(checkpoint.budgetSnapshot()),
                checkpoint.artifacts().stream()
                        .map(RunController::toResponse).toList());
    }

    private static BudgetResponse toResponse(BudgetSnapshot budget) {
        return new BudgetResponse(
                budget.modelCallsUsed(),
                budget.toolCallsUsed(),
                budget.tokensUsed(),
                budget.reactRoundsUsed());
    }

    private static ArtifactResponse toResponse(AgentArtifact artifact) {
        return new ArtifactResponse(
                artifact.artifactId(),
                artifact.taskId(),
                artifact.type(),
                artifact.schemaVersion(),
                artifact.artifactVersion(),
                artifact.status(),
                artifact.content(),
                artifact.sourceRefs(),
                artifact.createdAt());
    }

    private static RunEventResponse toResponse(RunEvent event) {
        return new RunEventResponse(
                event.sequence(),
                event.type(),
                event.payload(),
                event.createdAt());
    }

    public record StartRunRequest(
            @NotNull ExecutionPolicy requestedPolicy,
            @NotNull TaskProperties taskProperties) {
    }

    public record RunResponse(
            long runId,
            long campaignId,
            ExecutionPolicy requestedPolicy,
            ExecutionMode selectedMode,
            String selectorPolicyVersion,
            List<String> reasonCodes,
            Map<String, Object> featureSnapshot,
            long estimatedTokenBudget,
            RunState state,
            String failureReason,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record RunTraceResponse(
            RunResponse run,
            long lastEventSequence,
            CheckpointResponse checkpoint,
            List<RunEventResponse> events) {
    }

    public record CheckpointResponse(
            String checkpointType,
            int schemaVersion,
            int lastCompletedRound,
            long lastPersistedEventSequence,
            Instant createdAt,
            BudgetResponse budget,
            List<ArtifactResponse> artifacts) {
    }

    public record BudgetResponse(
            int modelCallsUsed,
            int toolCallsUsed,
            long tokensUsed,
            int reactRoundsUsed) {
    }

    public record ArtifactResponse(
            String artifactId,
            String taskId,
            ArtifactType type,
            String schemaVersion,
            int artifactVersion,
            ArtifactStatus status,
            Map<String, Object> content,
            List<String> sourceRefs,
            Instant createdAt) {
    }

    public record RunEventResponse(
            long sequence,
            RunEventType eventType,
            Map<String, Object> payload,
            Instant createdAt) {
    }
}
