package com.pulseink.agent.orchestration;

import com.pulseink.agent.api.AgentEngine;
import com.pulseink.agent.api.AgentExecutionObserver;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.plan.PlanParser;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.agent.plan.PlanTask;
import com.pulseink.agent.plan.PlanValidator;
import com.pulseink.agent.repair.ArtifactInvalidator;
import com.pulseink.agent.repair.RepairDecision;
import com.pulseink.agent.repair.RepairPath;
import com.pulseink.agent.repair.RepairRouter;
import com.pulseink.agent.repair.ReviewArtifactInterpreter;
import com.pulseink.agent.repair.StrictReviewArtifactInterpreter;
import com.pulseink.domain.content.ReviewAssessment;
import com.pulseink.domain.content.ReviewIssue;
import com.pulseink.domain.content.ReviewIssueType;
import com.pulseink.domain.execution.ExecutionMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;

/**
 * Centralized ORCHESTRATED engine with deterministic review repair. The coordinator owns Plan,
 * budget, invalidation and repair limits; role models only produce typed artifacts.
 */
public final class RunCoordinator implements AgentEngine {

    private static final Duration TASK_TIMEOUT = Duration.ofMinutes(5);

    private final RoleAgentRunner runner;
    private final PlanParser planParser;
    private final PlanValidator validator;
    private final ExecutorService executor;
    private final int maxParallelReadTasks;
    private final int maxContextCodePoints;
    private final RoleProfileFactory profileFactory;
    private final int plannerMaxModelCalls;
    private final ReviewArtifactInterpreter reviewInterpreter;
    private final RepairRouter repairRouter;
    private final ArtifactInvalidator artifactInvalidator;
    private final int maxRepairRounds;
    private final com.pulseink.agent.memory.ContextAssembler contextAssembler;

    public RunCoordinator(RoleAgentRunner runner,
                          PlanParser planParser,
                          PlanValidator validator,
                          ExecutorService executor,
                          int maxParallelReadTasks,
                          int maxContextCodePoints,
                          RoleProfileFactory profileFactory,
                          int plannerMaxModelCalls) {
        this(runner, planParser, validator, executor, maxParallelReadTasks,
                maxContextCodePoints, profileFactory, plannerMaxModelCalls,
                new StrictReviewArtifactInterpreter(), new RepairRouter(),
                new ArtifactInvalidator(), 2);
    }

    public RunCoordinator(RoleAgentRunner runner,
                          PlanParser planParser,
                          PlanValidator validator,
                          ExecutorService executor,
                          int maxParallelReadTasks,
                          int maxContextCodePoints,
                          RoleProfileFactory profileFactory,
                          int plannerMaxModelCalls,
                          ReviewArtifactInterpreter reviewInterpreter,
                          RepairRouter repairRouter,
                          ArtifactInvalidator artifactInvalidator,
                          int maxRepairRounds) {
        this(runner, planParser, validator, executor, maxParallelReadTasks,
                maxContextCodePoints, profileFactory, plannerMaxModelCalls,
                reviewInterpreter, repairRouter, artifactInvalidator, maxRepairRounds,
                null);
    }

    public RunCoordinator(RoleAgentRunner runner,
                          PlanParser planParser,
                          PlanValidator validator,
                          ExecutorService executor,
                          int maxParallelReadTasks,
                          int maxContextCodePoints,
                          RoleProfileFactory profileFactory,
                          int plannerMaxModelCalls,
                          ReviewArtifactInterpreter reviewInterpreter,
                          RepairRouter repairRouter,
                          ArtifactInvalidator artifactInvalidator,
                          int maxRepairRounds,
                          com.pulseink.agent.memory.ContextAssembler contextAssembler) {
        this.runner = Objects.requireNonNull(runner);
        this.planParser = Objects.requireNonNull(planParser);
        this.validator = Objects.requireNonNull(validator);
        this.executor = Objects.requireNonNull(executor);
        if (maxParallelReadTasks <= 0 || maxContextCodePoints <= 0
                || plannerMaxModelCalls <= 0) {
            throw new IllegalArgumentException("orchestration limits must be positive");
        }
        if (maxRepairRounds < 0 || maxRepairRounds > 2) {
            throw new IllegalArgumentException("maxRepairRounds must be between 0 and 2");
        }
        this.maxParallelReadTasks = maxParallelReadTasks;
        this.maxContextCodePoints = maxContextCodePoints;
        this.profileFactory = Objects.requireNonNull(profileFactory);
        this.plannerMaxModelCalls = plannerMaxModelCalls;
        this.reviewInterpreter = Objects.requireNonNull(reviewInterpreter);
        this.repairRouter = Objects.requireNonNull(repairRouter);
        this.artifactInvalidator = Objects.requireNonNull(artifactInvalidator);
        this.maxRepairRounds = maxRepairRounds;
        this.contextAssembler = contextAssembler;
    }

    @Override
    public ExecutionMode supportedMode() {
        return ExecutionMode.ORCHESTRATED;
    }

    @Override
    public AgentExecutionResult execute(AgentExecutionRequest request,
                                        AgentExecutionObserver observer) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        if (request.mode() != ExecutionMode.ORCHESTRATED) {
            throw new IllegalArgumentException(
                    "orchestrated engine only supports ORCHESTRATED requests");
        }
        var rootBudget = request.profile().executionBudget();
        var modelPolicy = request.profile().modelPolicy();
        if (rootBudget == null || modelPolicy == null) {
            return result(request, request.priorArtifacts(), request.budgetSnapshot(),
                    AgentTerminalReason.RUNTIME_FAILED, Metrics.ZERO);
        }
        OrchestrationBudgetLedger ledger;
        try {
            ledger = new OrchestrationBudgetLedger(rootBudget, request.budgetSnapshot());
        } catch (IllegalArgumentException invalidBudget) {
            return result(request, request.priorArtifacts(), request.budgetSnapshot(),
                    request.priorArtifacts().isEmpty()
                            ? AgentTerminalReason.RUNTIME_FAILED
                            : AgentTerminalReason.CHECKPOINT_INVALID,
                    Metrics.ZERO);
        }
        var observerLock = new Object();
        AgentExecutionObserver serializedObserver = event -> {
            synchronized (observerLock) {
                observer.onEvent(event);
            }
        };
        var history = new ArrayList<>(request.priorArtifacts());
        if (!basicHistoryValid(request.runId(), history)) {
            return result(request, history, ledger.snapshot(),
                    AgentTerminalReason.CHECKPOINT_INVALID, Metrics.ZERO);
        }

        var metrics = Metrics.ZERO;
        int completedRepairRounds = completedRepairRounds(history);
        PlanSpec plan;
        try {
            plan = restoreValidPlan(history);
        } catch (IllegalArgumentException corrupt) {
            return result(request, history, ledger.snapshot(),
                    AgentTerminalReason.CHECKPOINT_INVALID, metrics);
        }
        if (plan == null && history.stream().anyMatch(a ->
                a.status() == ArtifactStatus.VALID && a.type() != ArtifactType.PLAN)) {
            return result(request, history, ledger.snapshot(),
                    AgentTerminalReason.CHECKPOINT_INVALID, metrics);
        }

        while (true) {
            if (plan == null) {
                var planning = plan(request, history, ledger, modelPolicy,
                        rootBudget, serializedObserver);
                metrics = metrics.plus(planning.metrics());
                if (planning.terminalReason() != AgentTerminalReason.SUCCEEDED) {
                    return result(request, history, ledger.snapshot(),
                            planning.terminalReason(), metrics);
                }
                plan = planning.plan();
                int planVersion = maxVersion(history, "planner", ArtifactType.PLAN) + 1;
                var planArtifact = AgentArtifact.create(
                        "run-" + request.runId() + "-plan-v" + planVersion,
                        request.runId(), "planner", ArtifactType.PLAN, planVersion,
                        Map.of("plan", planArtifactContent(plan)), List.of(), Instant.now());
                history.add(planArtifact);
                serializedObserver.onEvent(new AgentRuntimeEvent.ArtifactCompleted(
                        request.runId(), Instant.now(), planArtifact,
                        ledger.snapshot(), completedRepairRounds));
                serializedObserver.onEvent(new AgentRuntimeEvent.TaskCompleted(
                        request.runId(), Instant.now(), "planner", AgentRole.PLANNER));
            }

            List<List<PlanTask>> stages;
            try {
                stages = validator.validate(plan, Set.of());
            } catch (IllegalArgumentException invalidPlan) {
                return result(request, history, ledger.snapshot(),
                        AgentTerminalReason.CHECKPOINT_INVALID, metrics);
            }
            var completedTaskIds = validateAndCompleted(request.runId(), plan, history);
            if (completedTaskIds == null) {
                return result(request, history, ledger.snapshot(),
                        AgentTerminalReason.CHECKPOINT_INVALID, metrics);
            }

            var contextRenderer = new ArtifactContextRenderer(maxContextCodePoints);
            AgentTerminalReason stageReason = AgentTerminalReason.SUCCEEDED;
            for (var stage : stages) {
                var readyTasks = stage.stream()
                        .filter(task -> !completedTaskIds.contains(task.taskId()))
                        .toList();
                if (readyTasks.isEmpty()) {
                    continue;
                }
                var outcome = runStage(request, serializedObserver, contextRenderer,
                        readyTasks, history, ledger, modelPolicy, rootBudget);
                metrics = metrics.plus(outcome.metrics());
                history.addAll(outcome.newArtifacts());
                if (outcome.terminalReason() != AgentTerminalReason.SUCCEEDED) {
                    stageReason = outcome.terminalReason();
                    break;
                }
                completedTaskIds.addAll(readyTasks.stream().map(PlanTask::taskId).toList());
            }

            if (stageReason == AgentTerminalReason.REPLAN_REQUESTED) {
                request.guard().assertCanProceed();
                var decision = replanDecision(plan, completedRepairRounds);
                if (decision.requiresHuman()) {
                    serializedObserver.onEvent(new AgentRuntimeEvent.RepairExhausted(
                            request.runId(), Instant.now(), completedRepairRounds));
                    return result(request, history, ledger.snapshot(),
                            AgentTerminalReason.HUMAN_INTERVENTION_REQUIRED, metrics);
                }
                completedRepairRounds = decision.nextRepairRound();
                history = invalidate(request.runId(), history, decision, true,
                        completedRepairRounds, ledger.snapshot(), serializedObserver);
                plan = null;
                continue;
            }
            if (stageReason != AgentTerminalReason.SUCCEEDED) {
                return result(request, history, ledger.snapshot(), stageReason, metrics);
            }

            ReviewAssessment assessment;
            try {
                assessment = assessLatestReviews(plan, history);
            } catch (IllegalArgumentException invalidReview) {
                return result(request, history, ledger.snapshot(),
                        AgentTerminalReason.INVALID_MODEL_OUTPUT, metrics);
            }
            if (assessment == null || assessment.passed()) {
                return result(request, orderArtifacts(history, stages), ledger.snapshot(),
                        AgentTerminalReason.SUCCEEDED, metrics);
            }
            for (var issue : assessment.issues()) {
                serializedObserver.onEvent(new AgentRuntimeEvent.ReviewIssueCreated(
                        request.runId(), Instant.now(), issue.type(),
                        issue.affectedTaskIds(), completedRepairRounds));
            }

            RepairDecision decision = repairRouter.route(
                    assessment, plan, completedRepairRounds, maxRepairRounds);
            if (decision.requiresHuman()) {
                serializedObserver.onEvent(new AgentRuntimeEvent.RepairExhausted(
                        request.runId(), Instant.now(), completedRepairRounds));
                return result(request, orderArtifacts(history, stages), ledger.snapshot(),
                        AgentTerminalReason.HUMAN_INTERVENTION_REQUIRED, metrics);
            }
            completedRepairRounds = decision.nextRepairRound();
            request.guard().assertCanProceed();
            history = invalidate(request.runId(), history, decision,
                    decision.requiresReplan(), completedRepairRounds,
                    ledger.snapshot(), serializedObserver);
            if (decision.requiresReplan()) {
                plan = null;
            }
        }
    }

    private PlanningStep plan(AgentExecutionRequest request,
                              List<AgentArtifact> history,
                              OrchestrationBudgetLedger ledger,
                              ModelPolicy modelPolicy,
                              ExecutionBudget rootBudget,
                              AgentExecutionObserver observer) {
        var plannerProfile = plannerProfile(modelPolicy, rootBudget)
                .restrictToolsTo(request.profile().allowedTools());
        OrchestrationBudgetLedger.Reservation reservation;
        try {
            reservation = ledger.reserve(plannerProfile.executionBudget());
        } catch (IllegalStateException exhausted) {
            return new PlanningStep(null, AgentTerminalReason.RUNTIME_FAILED, Metrics.ZERO);
        }
        observer.onEvent(new AgentRuntimeEvent.TaskStarted(
                request.runId(), Instant.now(), "planner", AgentRole.PLANNER));
        String planningObjective = request.objective();
        if (contextAssembler != null) {
            planningObjective = contextAssembler.assemble(
                    new com.pulseink.agent.memory.ContextAssemblyRequest(
                            request.runId(), plannerProfile, request.objective(),
                            "Plan the campaign execution", history,
                            request.campaignChannels(), maxContextCodePoints))
                    .renderedText();
        }
        var planningRequest = new AgentExecutionRequest(
                request.runId(), request.requestId(), request.mode(), request.profile(),
                planningObjective, history, ledger.snapshot(), request.approvalState(),
                request.taskId(), request.campaignChannels(), request.guard());
        PlanningOutcome outcome;
        try {
            outcome = runner.plan(planningRequest, plannerProfile, observer);
            ledger.settle(reservation, outcome.metrics());
        } catch (com.pulseink.agent.api.ExecutionOwnershipLostException ownershipLost) {
            ledger.release(reservation);
            throw ownershipLost;
        } catch (RuntimeException failure) {
            ledger.release(reservation);
            return new PlanningStep(null, AgentTerminalReason.RUNTIME_FAILED, Metrics.ZERO);
        }
        return new PlanningStep(outcome.plan(), outcome.terminalReason(),
                Metrics.from(outcome.metrics()));
    }

    private record PlanningStep(PlanSpec plan, AgentTerminalReason terminalReason,
                                Metrics metrics) {}

    private record StageOutcome(AgentTerminalReason terminalReason,
                                List<AgentArtifact> newArtifacts,
                                Metrics metrics) {}

    private StageOutcome runStage(AgentExecutionRequest request,
                                  AgentExecutionObserver observer,
                                  ArtifactContextRenderer contextRenderer,
                                  List<PlanTask> readyTasks,
                                  List<AgentArtifact> history,
                                  OrchestrationBudgetLedger ledger,
                                  ModelPolicy modelPolicy,
                                  ExecutionBudget rootBudget) {
        var artifacts = new ArrayList<AgentArtifact>();
        var metrics = Metrics.ZERO;
        for (int start = 0; start < readyTasks.size(); start += maxParallelReadTasks) {
            int end = Math.min(start + maxParallelReadTasks, readyTasks.size());
            var availableHistory = new ArrayList<>(history);
            availableHistory.addAll(artifacts);
            var outcome = runBatch(request, observer, contextRenderer,
                    readyTasks.subList(start, end), availableHistory,
                    ledger, modelPolicy, rootBudget);
            artifacts.addAll(outcome.newArtifacts());
            metrics = metrics.plus(outcome.metrics());
            if (outcome.terminalReason() != AgentTerminalReason.SUCCEEDED) {
                return new StageOutcome(outcome.terminalReason(),
                        List.copyOf(artifacts), metrics);
            }
        }
        return new StageOutcome(AgentTerminalReason.SUCCEEDED,
                List.copyOf(artifacts), metrics);
    }

    private StageOutcome runBatch(AgentExecutionRequest request,
                                  AgentExecutionObserver observer,
                                  ArtifactContextRenderer contextRenderer,
                                  List<PlanTask> tasks,
                                  List<AgentArtifact> history,
                                  OrchestrationBudgetLedger ledger,
                                  ModelPolicy modelPolicy,
                                  ExecutionBudget rootBudget) {
        request.guard().assertCanProceed();
        var scheduled = new ArrayList<ScheduledTask>();
        long batchTokenSlice = Math.max(1, ledger.availableTokens() / tasks.size());
        for (var task : tasks) {
            var profile = profileFactory.forRole(
                    task.role(), modelPolicy, rootBudget.deadline(), batchTokenSlice)
                    .restrictToolsTo(request.profile().allowedTools());
            OrchestrationBudgetLedger.Reservation reservation;
            try {
                reservation = ledger.reserve(profile.executionBudget());
            } catch (IllegalStateException oversubscribed) {
                for (var accepted : scheduled) {
                    ledger.release(accepted.reservation());
                }
                return new StageOutcome(reservationFailure(ledger), List.of(), Metrics.ZERO);
            }
            var dependencies = latestValidDependencies(task, history);
            var taskHistory = history.stream()
                    .filter(a -> a.taskId().equals(task.taskId()))
                    .sorted(Comparator.comparingInt(AgentArtifact::artifactVersion))
                    .toList();
            String context;
            if (contextAssembler != null) {
                var contextArtifacts = new ArrayList<>(dependencies);
                taskHistory.forEach(artifact -> {
                    if (!contextArtifacts.contains(artifact)) {
                        contextArtifacts.add(artifact);
                    }
                });
                context = contextAssembler.assemble(
                        new com.pulseink.agent.memory.ContextAssemblyRequest(
                                request.runId(), profile, request.objective(),
                                task.objective(), contextArtifacts,
                                request.campaignChannels(), maxContextCodePoints))
                        .renderedText();
            } else {
                context = contextRenderer.render(request.objective(), dependencies);
            }
            var roleRequest = new RoleTaskRequest(
                    request.runId(),
                    "run-" + request.runId() + "-task-" + task.taskId(),
                    task, context, dependencies, taskHistory,
                    profile.executionBudget(), request.approvalState(),
                    request.guard());
            var prior = new ArrayList<>(dependencies);
            taskHistory.forEach(a -> {
                if (!prior.contains(a)) {
                    prior.add(a);
                }
            });
            scheduled.add(new ScheduledTask(task, profile, roleRequest,
                    List.copyOf(prior), reservation));
        }

        AgentExecutionObserver roleObserver = event -> {
            if (!(event instanceof AgentRuntimeEvent.ArtifactCompleted)) {
                observer.onEvent(event);
            }
        };
        var calls = new ArrayList<java.util.concurrent.Callable<AgentExecutionResult>>();
        for (var item : scheduled) {
            observer.onEvent(new AgentRuntimeEvent.TaskStarted(
                    request.runId(), Instant.now(), item.task().taskId(), item.task().role()));
            calls.add(() -> runner.executeTask(
                    item.roleRequest(), item.profile(), roleObserver));
        }

        List<java.util.concurrent.Future<AgentExecutionResult>> futures;
        try {
            futures = executor.invokeAll(calls, TASK_TIMEOUT.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            scheduled.forEach(item -> ledger.release(item.reservation()));
            return new StageOutcome(AgentTerminalReason.RUNTIME_FAILED,
                    List.of(), Metrics.ZERO);
        }

        var newArtifacts = new ArrayList<AgentArtifact>();
        var metrics = Metrics.ZERO;
        var terminalReason = AgentTerminalReason.SUCCEEDED;
        for (int index = 0; index < futures.size(); index++) {
            var item = scheduled.get(index);
            AgentExecutionResult taskResult;
            try {
                taskResult = futures.get(index).get();
            } catch (Exception executionFailure) {
                ledger.release(item.reservation());
                if (executionFailure.getCause()
                        instanceof com.pulseink.agent.api.ExecutionOwnershipLostException lost) {
                    throw lost;
                }
                terminalReason = firstFailure(terminalReason,
                        AgentTerminalReason.RUNTIME_FAILED);
                observer.onEvent(new AgentRuntimeEvent.TaskCompleted(
                        request.runId(), Instant.now(), item.task().taskId(), item.task().role()));
                continue;
            }
            try {
                ledger.settle(item.reservation(), taskResult.metrics());
            } catch (IllegalStateException invalidUsage) {
                ledger.release(item.reservation());
                terminalReason = firstFailure(terminalReason,
                        AgentTerminalReason.RUNTIME_FAILED);
                continue;
            } finally {
                observer.onEvent(new AgentRuntimeEvent.TaskCompleted(
                        request.runId(), Instant.now(), item.task().taskId(), item.task().role()));
            }
            metrics = metrics.plus(Metrics.from(taskResult.metrics()));
            if (taskResult.terminalReason() != AgentTerminalReason.SUCCEEDED) {
                terminalReason = firstFailure(terminalReason, taskResult.terminalReason());
                continue;
            }
            for (var artifact : taskResult.artifacts()) {
                if (!item.priorArtifacts().contains(artifact)) {
                    newArtifacts.add(artifact);
                    observer.onEvent(new AgentRuntimeEvent.ArtifactCompleted(
                            request.runId(), Instant.now(), artifact,
                            ledger.snapshot(), taskResult.metrics().reactRounds()));
                }
            }
        }
        return new StageOutcome(terminalReason, List.copyOf(newArtifacts), metrics);
    }

    private record ScheduledTask(PlanTask task, AgentProfile profile,
                                 RoleTaskRequest roleRequest,
                                 List<AgentArtifact> priorArtifacts,
                                 OrchestrationBudgetLedger.Reservation reservation) {}

    private ArrayList<AgentArtifact> invalidate(long runId,
                                                List<AgentArtifact> history,
                                                RepairDecision decision,
                                                boolean includePlan,
                                                int repairRound,
                                                BudgetSnapshot budget,
                                                AgentExecutionObserver observer) {
        observer.onEvent(new AgentRuntimeEvent.RepairRoundStarted(
                runId, Instant.now(), repairRound, decision.path(), decision.rootTaskIds()));
        var invalidation = artifactInvalidator.invalidate(
                history, decision.invalidatedTaskIds(), includePlan);
        for (var artifact : invalidation.invalidatedArtifacts()) {
            observer.onEvent(new AgentRuntimeEvent.ArtifactInvalidated(
                    runId, Instant.now(), artifact, budget, repairRound));
        }
        return new ArrayList<>(invalidation.history());
    }

    private RepairDecision replanDecision(PlanSpec plan, int completedRounds) {
        if (completedRounds >= maxRepairRounds) {
            return new RepairDecision(RepairPath.WAITING_HUMAN,
                    Set.of(), Set.of(), false, true, completedRounds);
        }
        var tasks = new TreeSet<String>();
        plan.tasks().forEach(task -> tasks.add(task.taskId()));
        return new RepairDecision(RepairPath.PLANNER_REPLAN,
                Set.of(), tasks, true, false, completedRounds + 1);
    }

    private ReviewAssessment assessLatestReviews(PlanSpec plan,
                                                  List<AgentArtifact> history) {
        var reviewerTaskIds = new TreeSet<String>();
        plan.tasks().stream()
                .filter(task -> task.role() == AgentRole.REVIEWER)
                .forEach(task -> reviewerTaskIds.add(task.taskId()));
        if (reviewerTaskIds.isEmpty()) {
            return null;
        }
        var issues = new ArrayList<ReviewIssue>();
        boolean allPassed = true;
        for (var taskId : reviewerTaskIds) {
            var artifact = latestValid(history, taskId, ArtifactType.REVIEW_REPORT);
            if (artifact == null) {
                return null;
            }
            var assessment = reviewInterpreter.interpret(artifact, plan);
            allPassed &= assessment.passed();
            issues.addAll(assessment.issues());
        }
        return allPassed ? new ReviewAssessment(true, List.of())
                : new ReviewAssessment(false, issues);
    }

    private PlanSpec restoreValidPlan(List<AgentArtifact> history) {
        var plans = history.stream()
                .filter(a -> a.type() == ArtifactType.PLAN
                        && a.status() == ArtifactStatus.VALID)
                .toList();
        if (plans.size() > 1) {
            throw new IllegalArgumentException("multiple VALID plans");
        }
        if (plans.isEmpty()) {
            return null;
        }
        Object raw = plans.get(0).content().get("plan");
        if (!(raw instanceof String json)) {
            throw new IllegalArgumentException("plan content is missing");
        }
        return planParser.parse(json);
    }

    private boolean basicHistoryValid(long runId, List<AgentArtifact> history) {
        var ids = new HashSet<String>();
        var versions = new HashSet<String>();
        var validKeys = new HashSet<String>();
        for (var artifact : history) {
            String key = artifact.taskId() + "|" + artifact.type();
            if (artifact.runId() != runId || !ids.add(artifact.artifactId())
                    || !versions.add(key + "|" + artifact.artifactVersion())) {
                return false;
            }
            if (artifact.status() == ArtifactStatus.VALID && !validKeys.add(key)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> validateAndCompleted(long runId, PlanSpec plan,
                                             List<AgentArtifact> history) {
        var tasks = new HashMap<String, PlanTask>();
        plan.tasks().forEach(task -> tasks.put(task.taskId(), task));
        var completed = new HashSet<String>();
        for (var artifact : history) {
            if (artifact.runId() != runId || artifact.status() != ArtifactStatus.VALID
                    || artifact.type() == ArtifactType.PLAN) {
                continue;
            }
            var task = tasks.get(artifact.taskId());
            if (task == null || task.outputArtifactType() != artifact.type()
                    || !completed.add(task.taskId())) {
                return null;
            }
        }
        for (var taskId : completed) {
            if (!completed.containsAll(tasks.get(taskId).dependsOn())) {
                return null;
            }
        }
        return completed;
    }

    private static List<AgentArtifact> latestValidDependencies(
            PlanTask task, List<AgentArtifact> history) {
        var result = new ArrayList<AgentArtifact>();
        for (var dependency : task.dependsOn()) {
            history.stream()
                    .filter(a -> a.taskId().equals(dependency)
                            && a.status() == ArtifactStatus.VALID)
                    .max(Comparator.comparingInt(AgentArtifact::artifactVersion))
                    .ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    private static AgentArtifact latestValid(List<AgentArtifact> history,
                                             String taskId, ArtifactType type) {
        return history.stream()
                .filter(a -> a.taskId().equals(taskId) && a.type() == type
                        && a.status() == ArtifactStatus.VALID)
                .max(Comparator.comparingInt(AgentArtifact::artifactVersion))
                .orElse(null);
    }

    private static int completedRepairRounds(List<AgentArtifact> history) {
        int maxReviewVersion = history.stream()
                .filter(a -> a.type() == ArtifactType.REVIEW_REPORT)
                .mapToInt(AgentArtifact::artifactVersion)
                .max().orElse(0);
        return Math.max(0, maxReviewVersion - 1);
    }

    private static int maxVersion(List<AgentArtifact> history,
                                  String taskId, ArtifactType type) {
        return history.stream()
                .filter(a -> a.taskId().equals(taskId) && a.type() == type)
                .mapToInt(AgentArtifact::artifactVersion)
                .max().orElse(0);
    }

    private List<AgentArtifact> orderArtifacts(List<AgentArtifact> artifacts,
                                               List<List<PlanTask>> stages) {
        var ordered = new ArrayList<AgentArtifact>();
        artifacts.stream().filter(a -> a.type() == ArtifactType.PLAN)
                .sorted(Comparator.comparingInt(AgentArtifact::artifactVersion))
                .forEach(ordered::add);
        for (var stage : stages) {
            for (var task : stage) {
                artifacts.stream()
                        .filter(a -> a.taskId().equals(task.taskId())
                                && a.type() != ArtifactType.PLAN)
                        .sorted(Comparator.comparingInt(AgentArtifact::artifactVersion))
                        .forEach(a -> {
                            if (!ordered.contains(a)) {
                                ordered.add(a);
                            }
                        });
            }
        }
        artifacts.forEach(a -> {
            if (!ordered.contains(a)) {
                ordered.add(a);
            }
        });
        return List.copyOf(ordered);
    }

    private AgentProfile plannerProfile(ModelPolicy modelPolicy, ExecutionBudget rootBudget) {
        return profileFactory.forRole(AgentRole.PLANNER, modelPolicy,
                rootBudget.deadline(), rootBudget.maxTotalTokens(), plannerMaxModelCalls);
    }

    private static AgentTerminalReason reservationFailure(
            OrchestrationBudgetLedger ledger) {
        if (ledger.availableModelCalls() <= 0) {
            return AgentTerminalReason.MODEL_CALL_LIMIT_EXCEEDED;
        }
        if (ledger.availableToolCalls() <= 0) {
            return AgentTerminalReason.TOOL_CALL_LIMIT_EXCEEDED;
        }
        if (ledger.availableTokens() <= 0) {
            return AgentTerminalReason.TOKEN_LIMIT_EXCEEDED;
        }
        return AgentTerminalReason.RUNTIME_FAILED;
    }

    private static AgentTerminalReason firstFailure(AgentTerminalReason current,
                                                    AgentTerminalReason candidate) {
        return current == AgentTerminalReason.SUCCEEDED ? candidate : current;
    }

    private static AgentExecutionResult result(AgentExecutionRequest request,
                                               List<AgentArtifact> artifacts,
                                               BudgetSnapshot budget,
                                               AgentTerminalReason reason,
                                               Metrics metrics) {
        return new AgentExecutionResult(request.runId(), ExecutionMode.ORCHESTRATED,
                List.copyOf(artifacts), budget, reason, metrics.toResult());
    }

    private record Metrics(int modelCalls, int toolCalls, long tokens, int rounds) {
        private static final Metrics ZERO = new Metrics(0, 0, 0, 0);

        private Metrics plus(Metrics other) {
            return new Metrics(modelCalls + other.modelCalls,
                    toolCalls + other.toolCalls,
                    tokens + other.tokens,
                    rounds + other.rounds);
        }

        private AgentExecutionResult.Metrics toResult() {
            return new AgentExecutionResult.Metrics(modelCalls, toolCalls, tokens, rounds);
        }

        private static Metrics from(AgentExecutionResult.Metrics value) {
            return new Metrics(value.modelCalls(), value.toolCalls(),
                    value.totalTokens(), value.reactRounds());
        }
    }

    private static String planArtifactContent(PlanSpec plan) {
        var root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", plan.schemaVersion());
        var tasks = new ArrayList<Map<String, Object>>();
        for (var task : plan.tasks()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("taskId", task.taskId());
            item.put("role", task.role().name());
            item.put("objective", task.objective());
            item.put("dependsOn", task.dependsOn());
            item.put("requiredArtifactTypes", task.requiredArtifactTypes().stream()
                    .map(Enum::name).sorted().toList());
            item.put("outputArtifactType", task.outputArtifactType().name());
            item.put("access", task.access().name());
            tasks.add(item);
        }
        root.put("tasks", tasks);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("failed to serialize validated plan", impossible);
        }
    }
}
