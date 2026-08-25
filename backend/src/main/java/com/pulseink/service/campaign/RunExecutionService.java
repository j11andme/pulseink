package com.pulseink.service.campaign;

import com.pulseink.agent.api.AgentExecutionObserver;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.checkpoint.RunCheckpoint;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.RunCoordinator;
import com.pulseink.agent.react.DirectAgentEngine;
import com.pulseink.agent.react.UnifiedAgentRunner;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.service.content.CaptureRunContentUseCase;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Executes a campaign run through the governed engines. State CAS transitions, event appends and
 * checkpoints use short transactions; model and tool calls always run outside any database
 * transaction. ORCHESTRATED runs are delegated to the central {@code RunCoordinator}.
 */
public class RunExecutionService implements RunExecutionUseCase {

    private static final String CHECKPOINT_INVALID = "CHECKPOINT_INVALID";
    private static final Set<RunState> NON_AUTO_STATES = Set.of(
            RunState.WAITING_HUMAN,
            RunState.WAITING_APPROVAL,
            RunState.PUBLISHING,
            RunState.COMPLETED,
            RunState.FAILED,
            RunState.CANCELLED);

    private final RunRepository runRepository;
    private final CampaignRepository campaignRepository;
    private final RunEventService eventService;
    private final RunJournal journal;
    private final DirectAgentEngine directEngine;
    private final UnifiedAgentRunner reactEngine;
    private final RunCoordinator runCoordinator;
    private final CaptureRunContentUseCase captureRunContentUseCase;
    private final ModelPolicy modelPolicy;
    private final Executor executor;
    private final com.pulseink.agent.memory.ContextAssembler contextAssembler;
    private final com.pulseink.config.properties.MemoryProperties memoryProperties;
    private final RunLeaseManager leaseManager;
    private final Set<Long> activeLaunches = ConcurrentHashMap.newKeySet();

    public RunExecutionService(RunRepository runRepository,
                               CampaignRepository campaignRepository,
                               RunEventService eventService,
                               RunJournal journal,
                               DirectAgentEngine directEngine,
                               UnifiedAgentRunner reactEngine,
                               RunCoordinator runCoordinator,
                               CaptureRunContentUseCase captureRunContentUseCase,
                               ModelPolicy modelPolicy,
                               Executor executor,
                               com.pulseink.agent.memory.ContextAssembler contextAssembler,
                               com.pulseink.config.properties.MemoryProperties memoryProperties,
                               RunLeaseManager leaseManager) {
        this.runRepository = Objects.requireNonNull(runRepository);
        this.campaignRepository = Objects.requireNonNull(campaignRepository);
        this.eventService = Objects.requireNonNull(eventService);
        this.journal = Objects.requireNonNull(journal);
        this.directEngine = Objects.requireNonNull(directEngine);
        this.reactEngine = Objects.requireNonNull(reactEngine);
        this.runCoordinator = Objects.requireNonNull(runCoordinator);
        this.captureRunContentUseCase = Objects.requireNonNull(captureRunContentUseCase);
        this.modelPolicy = Objects.requireNonNull(modelPolicy);
        this.executor = Objects.requireNonNull(executor);
        this.contextAssembler = contextAssembler;
        this.memoryProperties = Objects.requireNonNull(memoryProperties);
        this.leaseManager = leaseManager;
    }

    /** Compatibility constructor for isolated execution tests without memory/lease wiring. */
    public RunExecutionService(RunRepository runRepository,
                               CampaignRepository campaignRepository,
                               RunEventService eventService,
                               RunJournal journal,
                               DirectAgentEngine directEngine,
                               UnifiedAgentRunner reactEngine,
                               RunCoordinator runCoordinator,
                               CaptureRunContentUseCase captureRunContentUseCase,
                               ModelPolicy modelPolicy,
                               Executor executor,
                               com.pulseink.agent.memory.ContextAssembler contextAssembler,
                               com.pulseink.config.properties.MemoryProperties memoryProperties) {
        this(runRepository, campaignRepository, eventService, journal, directEngine,
                reactEngine, runCoordinator, captureRunContentUseCase, modelPolicy, executor,
                contextAssembler, memoryProperties, null);
    }

    /** Compatibility constructor for isolated execution tests without memory wiring. */
    public RunExecutionService(RunRepository runRepository,
                               CampaignRepository campaignRepository,
                               RunEventService eventService,
                               RunJournal journal,
                               DirectAgentEngine directEngine,
                               UnifiedAgentRunner reactEngine,
                               RunCoordinator runCoordinator,
                               CaptureRunContentUseCase captureRunContentUseCase,
                               ModelPolicy modelPolicy,
                               Executor executor) {
        this(runRepository, campaignRepository, eventService, journal, directEngine,
                reactEngine, runCoordinator, captureRunContentUseCase, modelPolicy, executor,
                null, new com.pulseink.config.properties.MemoryProperties(
                        null, null, null, null, null, null, null, null));
    }

    /** Compatibility constructor for isolated execution tests without content persistence. */
    public RunExecutionService(RunRepository runRepository,
                               CampaignRepository campaignRepository,
                               RunEventService eventService,
                               RunJournal journal,
                               DirectAgentEngine directEngine,
                               UnifiedAgentRunner reactEngine,
                               RunCoordinator runCoordinator,
                               ModelPolicy modelPolicy,
                               Executor executor) {
        this(runRepository, campaignRepository, eventService, journal, directEngine,
                reactEngine, runCoordinator, (runId, result) -> {}, modelPolicy, executor);
    }

    @Override
    public void launch(long runId) {
        if (!activeLaunches.add(runId)) {
            return;
        }
        executor.execute(() -> {
            try {
                execute(runId);
            } catch (com.pulseink.agent.api.ExecutionOwnershipLostException lost) {
                // The lease is gone: stop this owner without writing any terminal state.
                org.slf4j.LoggerFactory.getLogger(RunExecutionService.class)
                        .warn("RUN_EXECUTION_OWNERSHIP_LOST runId={}", runId);
            } catch (RuntimeException ex) {
                failUnexpectedly(runId);
            } finally {
                activeLaunches.remove(runId);
            }
        });
    }

    @Override
    public AgentExecutionResult execute(long runId) {
        RunLeaseManager.LeaseHandle handle = leaseManager == null
                ? RunLeaseManager.LeaseHandle.noop()
                : leaseManager.tryAcquire(runId);
        if (!handle.owned()) {
            return null;
        }
        try {
            return executeOwned(runId, handle.guard());
        } finally {
            handle.close();
        }
    }

    private AgentExecutionResult executeOwned(
            long runId, com.pulseink.agent.api.ExecutionOwnershipGuard guard) {
        var run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "run " + runId + " was not found"));
        if (run.selectedMode() == null
                || NON_AUTO_STATES.contains(run.state())) {
            return null;
        }

        if (run.state() == RunState.CREATED) {
            guard.assertCanProceed();
            if (!beginExecution(run)) {
                return null;
            }
        }

        var campaign = campaignRepository.findById(run.campaignId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "campaign " + run.campaignId() + " was not found"));
        var checkpoint = latestValidCheckpoint(run);
        if (checkpoint == null && run.failureReason() != null
                && CHECKPOINT_INVALID.equals(run.failureReason())) {
            return null;
        }
        var priorArtifacts = checkpoint == null
                ? List.<AgentArtifact>of()
                : checkpoint.artifacts();
        var budgetSnapshot = checkpoint == null
                ? BudgetSnapshot.ZERO
                : checkpoint.budgetSnapshot();
        if (run.selectedMode() == ExecutionMode.DIRECT && !priorArtifacts.isEmpty()) {
            if (!isCompletedDirectCheckpoint(priorArtifacts)) {
                failRun(run, CHECKPOINT_INVALID);
                return null;
            }
            var recovered = new AgentExecutionResult(
                    runId,
                    ExecutionMode.DIRECT,
                    priorArtifacts,
                    budgetSnapshot,
                    AgentTerminalReason.SUCCEEDED,
                    new AgentExecutionResult.Metrics(
                            budgetSnapshot.modelCallsUsed(),
                            budgetSnapshot.toolCallsUsed(),
                            budgetSnapshot.tokensUsed(),
                            budgetSnapshot.reactRoundsUsed()));
            applyTerminalState(run, recovered);
            return recovered;
        }
        var profile = profileFor(run);
        String brief = campaignContext(campaign, run);
        String objective = brief;
        if (contextAssembler != null && run.selectedMode() != ExecutionMode.ORCHESTRATED) {
            var assembly = contextAssembler.assemble(
                    new com.pulseink.agent.memory.ContextAssemblyRequest(
                            runId, profile, brief, campaign.brief().objective(),
                            priorArtifacts, campaign.brief().channels(),
                            memoryProperties.contextMaxCodePoints()));
            objective = assembly.renderedText();
        }
        var request = new AgentExecutionRequest(
                runId,
                "run-" + runId,
                run.selectedMode(),
                profile,
                objective,
                priorArtifacts,
                budgetSnapshot,
                ApprovalState.NOT_REQUIRED,
                com.pulseink.agent.artifact.AgentArtifact.UNIFIED_TASK_ID,
                campaign.brief().channels(),
                guard);

        var observer = runtimeObserver(runId, priorArtifacts, guard);
        AgentExecutionResult result = switch (run.selectedMode()) {
            case DIRECT -> directEngine.execute(request, observer);
            case REACT -> reactEngine.execute(request, observer);
            case ORCHESTRATED -> runCoordinator.execute(request, observer);
        };
        if (result == null) {
            return null;
        }
        guard.assertCanProceed();
        applyTerminalState(run, result);
        return result;
    }

    private static String campaignContext(com.pulseink.domain.campaign.Campaign campaign,
                                          CampaignRun run) {
        var brief = campaign.brief();
        return "objective=" + brief.objective()
                + "; audience=" + brief.audience()
                + "; channels=" + brief.channels().stream()
                        .map(Enum::name).sorted().toList()
                + "; constraints=" + brief.constraints()
                + "; reasonCodes=" + run.selectionReasonCodes()
                + "; featureSnapshot=" + run.selectionFeatureSnapshot();
    }

    private boolean beginExecution(CampaignRun run) {
        run.beginExecution(Instant.now());
        try {
            runRepository.update(run);
        } catch (IllegalStateException stale) {
            return false;
        }
        eventService.appendAndPublish(run.id(), RunEventType.EXECUTION_MODE_SELECTED,
                payloadOf(Map.of(
                        "selectedMode", run.selectedMode().name(),
                        "selectorPolicyVersion", run.selectorPolicyVersion())));
        eventService.appendAndPublish(run.id(), RunEventType.RUN_STATE_CHANGED,
                payloadOf(Map.of(
                        "fromState", "CREATED",
                        "toState", "RUNNING",
                        "reasonCode", "EXECUTION_STARTED")));
        return true;
    }

    private RunCheckpoint latestValidCheckpoint(CampaignRun run) {
        RunCheckpoint checkpoint;
        try {
            checkpoint = journal.latestCheckpoint(run.id()).orElse(null);
        } catch (IllegalStateException corrupted) {
            failRun(run, CHECKPOINT_INVALID);
            return null;
        }
        if (checkpoint == null) {
            return null;
        }
        if (checkpoint.runId() != run.id()
                || checkpoint.schemaVersion() != RunCheckpoint.SUPPORTED_SCHEMA_VERSION) {
            failRun(run, CHECKPOINT_INVALID);
            return null;
        }
        return checkpoint;
    }

    private void failRun(CampaignRun run, String reasonCode) {
        var latest = reload(run);
        String fromState = latest.state().name();
        latest.fail(reasonCode);
        try {
            runRepository.update(latest);
        } catch (IllegalStateException ignored) {
            return;
        }
        eventService.appendAndPublish(latest.id(), RunEventType.RUN_STATE_CHANGED,
                payloadOf(Map.of(
                        "fromState", fromState,
                        "toState", "FAILED",
                        "reasonCode", reasonCode)));
    }

    private void applyTerminalState(CampaignRun run, AgentExecutionResult result) {
        var latest = reload(run);
        var reason = result.terminalReason();
        if (reason == AgentTerminalReason.SUCCEEDED
                || reason == AgentTerminalReason.APPROVAL_REQUIRED
                || reason == AgentTerminalReason.HUMAN_INTERVENTION_REQUIRED) {
            try {
                captureRunContentUseCase.capture(latest.id(), result);
            } catch (RuntimeException captureFailure) {
                String fromState = latest.state().name();
                latest.fail(AgentTerminalReason.RUNTIME_FAILED.name());
                try {
                    runRepository.update(latest);
                } catch (IllegalStateException stale) {
                    return;
                }
                try {
                    eventService.appendAndPublish(latest.id(), RunEventType.RUNTIME_FAILED,
                            payloadOf(Map.of("reasonCode",
                                    AgentTerminalReason.RUNTIME_FAILED.name())));
                    eventService.appendAndPublish(latest.id(), RunEventType.RUN_STATE_CHANGED,
                            payloadOf(Map.of(
                                    "fromState", fromState,
                                    "toState", RunState.FAILED.name(),
                                    "reasonCode",
                                    AgentTerminalReason.RUNTIME_FAILED.name())));
                } catch (RuntimeException ignored) {
                    // The durable FAILED state remains authoritative.
                }
                return;
            }
        }
        switch (reason) {
            case SUCCEEDED, APPROVAL_REQUIRED -> {
                if (reason == AgentTerminalReason.APPROVAL_REQUIRED) {
                    eventService.appendAndPublish(latest.id(), RunEventType.APPROVAL_REQUIRED,
                            payloadOf(Map.of("reasonCode", "APPROVAL_REQUIRED")));
                }
                latest.requestApproval();
                updateState(latest, "RUNNING", "WAITING_APPROVAL", reason.name());
            }
            case REPLAN_REQUESTED -> {
                latest.waitForHuman();
                updateState(latest, "RUNNING", "WAITING_HUMAN", "REPLAN_REQUESTED");
            }
            case HUMAN_INTERVENTION_REQUIRED -> {
                latest.waitForHuman();
                updateState(latest, "RUNNING", "WAITING_HUMAN",
                        "HUMAN_INTERVENTION_REQUIRED");
            }
            case MODEL_CALL_LIMIT_EXCEEDED, TOOL_CALL_LIMIT_EXCEEDED,
                     TOKEN_LIMIT_EXCEEDED, REACT_ROUND_LIMIT_EXCEEDED,
                     DEADLINE_EXCEEDED, INVALID_MODEL_OUTPUT,
                     MODEL_FAILURE, TOOL_FAILURE, CHECKPOINT_INVALID, RUNTIME_FAILED -> {
                latest.fail(reason.name());
                updateState(latest, "RUNNING", "FAILED", reason.name());
            }
        }
    }

    private CampaignRun reload(CampaignRun run) {
        return runRepository.findById(run.id()).orElse(run);
    }

    private static boolean isCompletedDirectCheckpoint(List<AgentArtifact> artifacts) {
        return artifacts.size() == 1
                && artifacts.get(0).type()
                == com.pulseink.agent.artifact.ArtifactType.CONTENT_DRAFT;
    }

    private void failUnexpectedly(long runId) {
        CampaignRun run;
        try {
            run = runRepository.findById(runId).orElse(null);
        } catch (RuntimeException ignored) {
            return;
        }
        if (run == null || run.state().isTerminal()
                || run.state() == RunState.WAITING_HUMAN
                || run.state() == RunState.WAITING_APPROVAL
                || run.state() == RunState.PUBLISHING) {
            return;
        }
        String fromState = run.state().name();
        try {
            run.fail(AgentTerminalReason.RUNTIME_FAILED.name());
            runRepository.update(run);
        } catch (RuntimeException ignored) {
            return;
        }
        try {
            eventService.appendAndPublish(runId, RunEventType.RUNTIME_FAILED,
                    payloadOf(Map.of("reasonCode", AgentTerminalReason.RUNTIME_FAILED.name())));
            eventService.appendAndPublish(runId, RunEventType.RUN_STATE_CHANGED,
                    payloadOf(Map.of(
                            "fromState", fromState,
                            "toState", RunState.FAILED.name(),
                            "reasonCode", AgentTerminalReason.RUNTIME_FAILED.name())));
        } catch (RuntimeException ignored) {
            // The durable FAILED state is authoritative when the journal is unavailable.
        }
    }

    private void updateState(CampaignRun run, String fromState, String toState,
                             String reasonCode) {
        try {
            runRepository.update(run);
        } catch (IllegalStateException stale) {
            return;
        }
        eventService.appendAndPublish(run.id(), RunEventType.RUN_STATE_CHANGED,
                payloadOf(Map.of(
                        "fromState", fromState,
                        "toState", toState,
                        "reasonCode", reasonCode)));
    }

    private AgentProfile profileFor(CampaignRun run) {
        var deadline = Instant.now().plus(Duration.ofMinutes(30));
        var base = switch (run.selectedMode()) {
            case DIRECT -> ExecutionBudget.defaultDirect(deadline);
            case REACT -> ExecutionBudget.defaultReact(deadline);
            case ORCHESTRATED -> new ExecutionBudget(
                    100, 100, 64_000L, 100, 1, deadline);
        };
        long tokenBudget = run.estimatedTokenBudget() > 0
                ? run.estimatedTokenBudget()
                : base.maxTotalTokens();
        var budget = new ExecutionBudget(
                base.maxModelCalls(),
                base.maxToolCalls(),
                tokenBudget,
                base.maxReactRounds(),
                base.invalidOutputRetries(),
                deadline);
        var tools = run.selectedMode() == ExecutionMode.DIRECT
                ? Set.<String>of()
                : Set.of("builtin.deterministic_validate", "builtin.knowledge_search");
        return AgentProfile.unified("unified", tools, modelPolicy, budget);
    }

    private AgentExecutionObserver runtimeObserver(
            long runId, List<AgentArtifact> priorArtifacts,
            com.pulseink.agent.api.ExecutionOwnershipGuard guard) {
        var completedArtifacts = new java.util.ArrayList<AgentArtifact>(priorArtifacts);
        return event -> {
            switch (event) {
                case AgentRuntimeEvent.DecisionRecorded decision ->
                        eventService.appendAndPublish(runId, RunEventType.DECISION_RECORDED,
                                payloadOf(Map.of(
                                        "decisionType", decision.decisionType(),
                                        "decisionSummary", decision.decisionSummary())));
                case AgentRuntimeEvent.ToolCallStarted started -> {
                    var payload = new LinkedHashMap<String, Object>();
                    payload.put("qualifiedName", started.qualifiedName());
                    payload.put("argumentNames", started.arguments().keySet().stream()
                            .sorted()
                            .toList());
                    eventService.appendAndPublish(runId, RunEventType.TOOL_CALL_STARTED, payload);
                }
                case AgentRuntimeEvent.ToolCallCompleted completed ->
                        eventService.appendAndPublish(runId, RunEventType.TOOL_CALL_COMPLETED,
                                payloadOf(Map.of(
                                        "qualifiedName", completed.qualifiedName(),
                                        "observation", completed.observationSummary())));
                case AgentRuntimeEvent.ArtifactCompleted artifact -> {
                    synchronized (completedArtifacts) {
                        if (artifact.artifact().type()
                                == com.pulseink.agent.artifact.ArtifactType.PLAN) {
                            eventService.appendAndPublish(runId, RunEventType.PLAN_VALIDATED,
                                    payloadOf(Map.of(
                                            "artifactId", artifact.artifact().artifactId(),
                                            "schemaVersion", artifact.artifact().schemaVersion())));
                        }
                        completedArtifacts.add(artifact.artifact());
                        guard.assertCanProceed();
                        var checkpoint = RunCheckpoint.of(
                                runId,
                                "ARTIFACT",
                                List.copyOf(completedArtifacts),
                                artifact.budgetSnapshot(),
                                artifact.completedRound(),
                                0L,
                                artifact.timestamp());
                        eventService.saveCheckpointAndPublish(
                                checkpoint,
                                RunEventType.ARTIFACT_CREATED,
                                payloadOf(Map.of(
                                        "artifactId", artifact.artifact().artifactId(),
                                        "artifactType", artifact.artifact().type().name(),
                                        "artifactVersion",
                                        artifact.artifact().artifactVersion())));
                    }
                }
                case AgentRuntimeEvent.RuntimeFailed failed ->
                        eventService.appendAndPublish(runId, RunEventType.RUNTIME_FAILED,
                                payloadOf(Map.of("reasonCode", failed.reason().name())));
                case AgentRuntimeEvent.TaskStarted started ->
                        eventService.appendAndPublish(runId, RunEventType.TASK_STARTED,
                                payloadOf(Map.of(
                                        "taskId", started.taskId(),
                                        "role", started.role().name())));
                case AgentRuntimeEvent.TaskCompleted completed ->
                        eventService.appendAndPublish(runId, RunEventType.TASK_COMPLETED,
                                payloadOf(Map.of(
                                        "taskId", completed.taskId(),
                                        "role", completed.role().name())));
                case AgentRuntimeEvent.ReviewIssueCreated issue ->
                        eventService.appendAndPublish(runId,
                                RunEventType.REVIEW_ISSUE_CREATED,
                                payloadOf(Map.of(
                                        "issueType", issue.issueType().name(),
                                        "affectedTaskIds", issue.affectedTaskIds().stream()
                                                .sorted().toList(),
                                        "repairRound", issue.repairRound())));
                case AgentRuntimeEvent.RepairRoundStarted repair ->
                        eventService.appendAndPublish(runId,
                                RunEventType.REPAIR_ROUND_STARTED,
                                payloadOf(Map.of(
                                        "repairRound", repair.repairRound(),
                                        "path", repair.path().name(),
                                        "rootTaskIds", repair.rootTaskIds().stream()
                                                .sorted().toList())));
                case AgentRuntimeEvent.ArtifactInvalidated invalidated -> {
                    synchronized (completedArtifacts) {
                        completedArtifacts.replaceAll(existing ->
                                existing.artifactId().equals(
                                        invalidated.artifact().artifactId())
                                        ? invalidated.artifact() : existing);
                        guard.assertCanProceed();
                        var checkpoint = RunCheckpoint.of(
                                runId,
                                "REPAIR",
                                List.copyOf(completedArtifacts),
                                invalidated.budgetSnapshot(),
                                invalidated.repairRound(),
                                0L,
                                invalidated.timestamp());
                        eventService.saveCheckpointAndPublish(
                                checkpoint,
                                RunEventType.ARTIFACT_INVALIDATED,
                                payloadOf(Map.of(
                                        "artifactId", invalidated.artifact().artifactId(),
                                        "artifactType", invalidated.artifact().type().name(),
                                        "taskId", invalidated.artifact().taskId(),
                                        "repairRound", invalidated.repairRound())));
                    }
                }
                case AgentRuntimeEvent.RepairExhausted exhausted ->
                        eventService.appendAndPublish(runId,
                                RunEventType.REPAIR_EXHAUSTED,
                                payloadOf(Map.of(
                                        "completedRepairRounds",
                                        exhausted.completedRepairRounds())));
            }
        };
    }

    private static Map<String, Object> payloadOf(Map<String, Object> values) {
        var payload = new LinkedHashMap<String, Object>();
        payload.putAll(values);
        return payload;
    }
}
