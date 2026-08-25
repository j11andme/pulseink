package com.pulseink.service.content;

import static com.pulseink.service.content.ContentErrorCode.CONTENT_ALREADY_APPROVED;
import static com.pulseink.service.content.ContentErrorCode.CONTENT_NOT_FOUND;
import static com.pulseink.service.content.ContentErrorCode.CONTENT_NOT_LATEST;
import static com.pulseink.service.content.ContentErrorCode.CONTENT_VERSION_CONFLICT;
import static com.pulseink.service.content.ContentErrorCode.CONTENT_VERSION_NOT_FOUND;
import static com.pulseink.service.content.ContentErrorCode.RUN_NOT_EDITABLE;
import static com.pulseink.service.content.ContentErrorCode.RUN_NOT_WAITING_APPROVAL;

import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.plan.PlanParser;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.agent.repair.ReviewArtifactInterpreter;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.content.ApprovalRecord;
import com.pulseink.domain.content.ContentItem;
import com.pulseink.domain.content.ContentVersion;
import com.pulseink.domain.content.ReviewReport;
import com.pulseink.service.campaign.RunRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.support.TransactionTemplate;

public final class ContentWorkflowService implements CaptureRunContentUseCase,
        QueryContentUseCase, CreateContentVersionUseCase, ApproveContentUseCase {

    private static final Set<AgentTerminalReason> CAPTURABLE_REASONS = Set.of(
            AgentTerminalReason.SUCCEEDED,
            AgentTerminalReason.APPROVAL_REQUIRED,
            AgentTerminalReason.HUMAN_INTERVENTION_REQUIRED);

    private final ContentWorkflowRepository repository;
    private final RunRepository runRepository;
    private final ReviewArtifactInterpreter reviewInterpreter;
    private final PlanParser planParser;
    private final TransactionTemplate transactions;

    public ContentWorkflowService(ContentWorkflowRepository repository,
                                  RunRepository runRepository,
                                  ReviewArtifactInterpreter reviewInterpreter,
                                  PlanParser planParser,
                                  TransactionTemplate transactions) {
        this.repository = Objects.requireNonNull(repository);
        this.runRepository = Objects.requireNonNull(runRepository);
        this.reviewInterpreter = Objects.requireNonNull(reviewInterpreter);
        this.planParser = Objects.requireNonNull(planParser);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public void capture(long runId, AgentExecutionResult result) {
        Objects.requireNonNull(result, "result must not be null");
        if (result.runId() != runId) {
            throw new IllegalArgumentException("result does not belong to run " + runId);
        }
        if (!CAPTURABLE_REASONS.contains(result.terminalReason())) {
            return;
        }
        var ordered = result.artifacts().stream()
                .sorted(Comparator.comparing(AgentArtifact::taskId)
                        .thenComparingInt(AgentArtifact::artifactVersion)
                        .thenComparing(AgentArtifact::artifactId))
                .toList();
        var reviewPlan = combinedReviewPlan(ordered);
        transactions.execute(ignored -> {
            for (var artifact : ordered) {
                if (artifact.type() == ArtifactType.CONTENT_DRAFT) {
                    repository.captureAgentVersion(runId, artifact.taskId(), artifact);
                } else if (artifact.type() == ArtifactType.REVIEW_REPORT) {
                    if (reviewPlan == null) {
                        throw new IllegalArgumentException(
                                "review artifact has no corresponding plan");
                    }
                    var assessment = reviewInterpreter.interpret(
                            artifact.withStatus(ArtifactStatus.VALID), reviewPlan);
                    repository.captureReview(runId, artifact, assessment,
                            Math.max(0, artifact.artifactVersion() - 1));
                }
            }
            return null;
        });
    }

    @Override
    public List<ContentItem> findByRunId(long runId) {
        if (runId <= 0) throw validation("run id must be positive");
        return repository.findByRunId(runId);
    }

    @Override
    public ContentItem get(long contentId) {
        if (contentId <= 0) throw validation("content id must be positive");
        return repository.findById(contentId).orElseThrow(() ->
                new ContentWorkflowException(CONTENT_NOT_FOUND,
                        "content " + contentId + " was not found"));
    }

    @Override
    public List<ReviewReport> findReviewsByRunId(long runId) {
        if (runId <= 0) throw validation("run id must be positive");
        return repository.findReviewsByRunId(runId);
    }

    @Override
    public ContentVersion createVersion(CreateContentVersionUseCase.Command command) {
        Objects.requireNonNull(command, "command must not be null");
        validateEdit(command);
        return transactions.execute(status -> {
            var item = get(command.contentId());
            var run = requireRun(item.runId());
            if (run.state() != RunState.WAITING_HUMAN
                    && run.state() != RunState.WAITING_APPROVAL) {
                throw new ContentWorkflowException(RUN_NOT_EDITABLE,
                        "run " + run.id() + " is not editable");
            }
            ContentVersion created;
            try {
                created = repository.appendHumanVersion(item.id(),
                        command.expectedCurrentVersionNo(), command.expectedItemVersion(),
                        command.content(), command.sourceRefs(), command.actorUserId());
            } catch (IllegalStateException conflict) {
                throw new ContentWorkflowException(CONTENT_VERSION_CONFLICT,
                        "content " + item.id() + " changed concurrently");
            }
            if (run.state() == RunState.WAITING_HUMAN) {
                run.resumeForApproval();
                runRepository.update(run);
            }
            return created;
        });
    }

    @Override
    public ApprovalRecord approve(ApproveContentUseCase.Command command) {
        Objects.requireNonNull(command, "command must not be null");
        validateApproval(command);
        return transactions.execute(status -> {
            var item = get(command.contentId());
            var run = requireRun(item.runId());
            if (run.state() != RunState.WAITING_APPROVAL) {
                throw new ContentWorkflowException(RUN_NOT_WAITING_APPROVAL,
                        "run " + run.id() + " is not waiting for approval");
            }
            var version = item.versions().stream()
                    .filter(candidate -> candidate.id() == command.contentVersionId())
                    .findFirst().orElseThrow(() -> new ContentWorkflowException(
                            CONTENT_VERSION_NOT_FOUND,
                            "content version " + command.contentVersionId() + " was not found"));
            if (version.versionNo() != item.currentVersionNo()
                    || command.expectedCurrentVersionNo() != item.currentVersionNo()) {
                throw new ContentWorkflowException(CONTENT_NOT_LATEST,
                        "only the latest content version can be approved");
            }
            if (version.sourceArtifactStatus() == ArtifactStatus.INVALIDATED) {
                throw new ContentWorkflowException(CONTENT_NOT_LATEST,
                        "invalidated content cannot be approved");
            }
            if (item.approvals().stream().anyMatch(existing ->
                    existing.contentVersionId() == version.id())) {
                throw new ContentWorkflowException(CONTENT_ALREADY_APPROVED,
                        "content version is already approved");
            }
            try {
                return repository.approve(item.id(), version.id(),
                        command.expectedCurrentVersionNo(), command.expectedItemVersion(),
                        command.comment(), command.actorUserId());
            } catch (IllegalStateException conflict) {
                throw new ContentWorkflowException(CONTENT_VERSION_CONFLICT,
                        "content " + item.id() + " changed concurrently");
            }
        });
    }

    private PlanSpec combinedReviewPlan(List<AgentArtifact> artifacts) {
        var creatorTasks = new LinkedHashMap<String, com.pulseink.agent.plan.PlanTask>();
        for (var artifact : artifacts) {
            if (artifact.type() != ArtifactType.PLAN) continue;
            Object serialized = artifact.content().get("plan");
            if (!(serialized instanceof String planJson)) {
                throw new IllegalArgumentException("PLAN artifact content.plan must be a string");
            }
            var parsed = planParser.parse(planJson);
            for (var task : parsed.tasks()) {
                if (task.role() == com.pulseink.agent.orchestration.AgentRole.CREATOR) {
                    creatorTasks.put(task.taskId(), task);
                }
            }
        }
        return creatorTasks.isEmpty() ? null
                : new PlanSpec(PlanSpec.SUPPORTED_SCHEMA_VERSION,
                        new ArrayList<>(creatorTasks.values()));
    }

    private com.pulseink.domain.campaign.CampaignRun requireRun(long runId) {
        return runRepository.findById(runId).orElseThrow(() ->
                new ContentWorkflowException(RUN_NOT_EDITABLE,
                        "run " + runId + " was not found"));
    }

    private void validateEdit(CreateContentVersionUseCase.Command command) {
        if (command.contentId() <= 0 || command.expectedCurrentVersionNo() <= 0
                || command.expectedItemVersion() < 0 || command.actorUserId() <= 0
                || command.content().isEmpty()) {
            throw validation("content version request is invalid");
        }
    }

    private void validateApproval(ApproveContentUseCase.Command command) {
        if (command.contentId() <= 0 || command.contentVersionId() <= 0
                || command.expectedCurrentVersionNo() <= 0
                || command.expectedItemVersion() < 0 || command.actorUserId() <= 0) {
            throw validation("approval request is invalid");
        }
    }

    private ContentWorkflowException validation(String message) {
        return new ContentWorkflowException(ContentErrorCode.VALIDATION_ERROR, message);
    }
}
