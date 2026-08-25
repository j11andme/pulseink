package com.pulseink.agent.orchestration;

import com.pulseink.agent.api.AgentExecutionObserver;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.BudgetTracker;
import com.pulseink.agent.model.ModelCallException;
import com.pulseink.agent.model.ModelCompletion;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.plan.PlanParser;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.agent.plan.PlanValidator;
import com.pulseink.agent.react.ReactLoop;
import com.pulseink.domain.execution.ExecutionMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Role execution boundary. The Planner uses a strict structured plan protocol with one format
 * repair and one retryable fallback; all other roles run through the shared {@link ReactLoop}
 * with profile-controlled prompt, tools and output allowlist.
 */
public final class RoleAgentRunner {

    private static final String PLANNER_SYSTEM_PROMPT_SUFFIX =
            "\nReturn one PlanSpec JSON object only. Never include Markdown fences.";
    private static final String REPAIR_SUFFIX =
            "\nYour previous output was not valid. Return a single valid PlanSpec JSON object.";

    private final ModelRouter router;
    private final PlanParser planParser;
    private final PlanValidator validator;
    private final ReactLoop reactLoop;
    private final int maxOutputTokensPerCall;
    private final Duration completionTimeout;
    private final Double temperature;

    public RoleAgentRunner(ModelRouter router, PlanParser planParser,
                           PlanValidator validator, ReactLoop reactLoop) {
        this(router, planParser, validator, reactLoop,
                ReactLoop.DEFAULT_MAX_OUTPUT_TOKENS_PER_CALL,
                ReactLoop.DEFAULT_COMPLETION_TIMEOUT);
    }

    public RoleAgentRunner(ModelRouter router, PlanParser planParser,
                           PlanValidator validator, ReactLoop reactLoop,
                           int maxOutputTokensPerCall, Duration completionTimeout) {
        this(router, planParser, validator, reactLoop, maxOutputTokensPerCall,
                completionTimeout, null);
    }

    public RoleAgentRunner(ModelRouter router, PlanParser planParser,
                           PlanValidator validator, ReactLoop reactLoop,
                           int maxOutputTokensPerCall, Duration completionTimeout,
                           Double temperature) {
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.planParser = Objects.requireNonNull(planParser, "planParser must not be null");
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.reactLoop = Objects.requireNonNull(reactLoop, "reactLoop must not be null");
        if (maxOutputTokensPerCall <= 0) {
            throw new IllegalArgumentException("maxOutputTokensPerCall must be positive");
        }
        this.maxOutputTokensPerCall = maxOutputTokensPerCall;
        this.completionTimeout = Objects.requireNonNull(
                completionTimeout, "completionTimeout must not be null");
        if (completionTimeout.isZero() || completionTimeout.isNegative()) {
            throw new IllegalArgumentException("completionTimeout must be positive");
        }
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        this.temperature = temperature;
    }

    public PlanningOutcome plan(AgentExecutionRequest rootRequest,
                                 AgentProfile plannerProfile,
                                 AgentExecutionObserver observer) {
        Objects.requireNonNull(rootRequest, "rootRequest must not be null");
        Objects.requireNonNull(plannerProfile, "plannerProfile must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        var excluded = new HashSet<String>();
        var budget = new BudgetTracker(plannerProfile.executionBudget());
        rootRequest.guard().assertCanProceed();

        com.pulseink.agent.model.ModelRoute route;
        try {
            route = router.route(plannerProfile, excluded);
        } catch (IllegalStateException noRoute) {
            return outcome(null, budget, AgentTerminalReason.MODEL_FAILURE);
        }
        var attempt = callPlanner(
                rootRequest, plannerProfile, route.modelPort(), false, budget);
        if (attempt.terminalReason() != null) {
            return outcome(null, budget, attempt.terminalReason());
        }
        if (attempt.completion() == null && attempt.sameProviderRetryable()) {
            rootRequest.guard().assertCanProceed();
            attempt = callPlanner(
                    rootRequest, plannerProfile, route.modelPort(), false, budget);
            if (attempt.terminalReason() != null) {
                return outcome(null, budget, attempt.terminalReason());
            }
        }
        if (attempt.completion() == null) {
            if (!attempt.retryable()) {
                return outcome(null, budget, AgentTerminalReason.MODEL_FAILURE);
            }
            excluded.add(route.providerId());
            try {
                route = router.route(plannerProfile, excluded);
            } catch (IllegalStateException noRoute) {
                return outcome(null, budget, AgentTerminalReason.MODEL_FAILURE);
            }
            rootRequest.guard().assertCanProceed();
            attempt = callPlanner(
                    rootRequest, plannerProfile, route.modelPort(), false, budget);
            if (attempt.terminalReason() != null) {
                return outcome(null, budget, attempt.terminalReason());
            }
            if (attempt.completion() == null) {
                return outcome(null, budget, AgentTerminalReason.MODEL_FAILURE);
            }
        }
        var completion = attempt.completion();

        PlanSpec plan;
        try {
            plan = planParser.parse(completion.content());
        } catch (IllegalArgumentException invalid) {
            rootRequest.guard().assertCanProceed();
            var repairedAttempt = callPlanner(
                    rootRequest, plannerProfile, route.modelPort(), true, budget);
            if (repairedAttempt.terminalReason() != null) {
                return outcome(null, budget, repairedAttempt.terminalReason());
            }
            var repaired = repairedAttempt.completion();
            if (repaired == null) {
                return outcome(null, budget, AgentTerminalReason.MODEL_FAILURE);
            }
            try {
                plan = planParser.parse(repaired.content());
            } catch (IllegalArgumentException stillInvalid) {
                return outcome(null, budget, AgentTerminalReason.INVALID_MODEL_OUTPUT);
            }
        }
        var initialTypes = new HashSet<ArtifactType>();
        for (var artifact : rootRequest.priorArtifacts()) {
            if (artifact.status()
                    == com.pulseink.agent.artifact.ArtifactStatus.VALID
                    && artifact.type() != ArtifactType.PLAN) {
                initialTypes.add(artifact.type());
            }
        }
        try {
            validator.validate(plan, initialTypes);
        } catch (IllegalArgumentException invalidPlan) {
            return outcome(null, budget, AgentTerminalReason.INVALID_MODEL_OUTPUT);
        }
        return outcome(plan, budget, AgentTerminalReason.SUCCEEDED);
    }

    public AgentExecutionResult executeTask(RoleTaskRequest request,
                                            AgentProfile roleProfile,
                                            AgentExecutionObserver observer) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(roleProfile, "roleProfile must not be null");
        var priorArtifacts = new ArrayList<com.pulseink.agent.artifact.AgentArtifact>();
        priorArtifacts.addAll(request.dependencyArtifacts());
        for (var artifact : request.taskArtifacts()) {
            if (!priorArtifacts.contains(artifact)) {
                priorArtifacts.add(artifact);
            }
        }
        var executionRequest = new AgentExecutionRequest(
                request.runId(),
                request.requestId(),
                ExecutionMode.ORCHESTRATED,
                roleProfile,
                request.campaignContext() + "\nTask objective: " + request.task().objective(),
                List.copyOf(priorArtifacts),
                BudgetSnapshot.ZERO,
                request.approvalState(),
                request.task().taskId(),
                List.of(),
                request.guard());
        var result = reactLoop.execute(executionRequest, observer);
        if (result.terminalReason() != AgentTerminalReason.SUCCEEDED) {
            return result;
        }
        var newArtifacts = new ArrayList<com.pulseink.agent.artifact.AgentArtifact>();
        for (var artifact : result.artifacts()) {
            if (!priorArtifacts.contains(artifact)) {
                newArtifacts.add(artifact);
            }
        }
        boolean valid = newArtifacts.size() == 1
                && newArtifacts.get(0).taskId().equals(request.task().taskId())
                && (roleProfile.allowedArtifactTypes().isEmpty()
                        || roleProfile.allowedArtifactTypes()
                                .contains(newArtifacts.get(0).type()));
        if (!valid) {
            return new AgentExecutionResult(
                    request.runId(),
                    ExecutionMode.ORCHESTRATED,
                    List.copyOf(priorArtifacts),
                    result.finalBudget(),
                    AgentTerminalReason.INVALID_MODEL_OUTPUT,
                    result.metrics());
        }
        return result;
    }

    private PlannerAttempt callPlanner(AgentExecutionRequest rootRequest,
                                       AgentProfile plannerProfile,
                                       com.pulseink.agent.model.AgentModelPort modelPort,
                                       boolean repair,
                                       BudgetTracker budget) {
        String systemPrompt = (plannerProfile.systemPrompt() == null
                ? "You are PulseInk Planner."
                : plannerProfile.systemPrompt())
                + PLANNER_SYSTEM_PROMPT_SUFFIX;
        var request = new ModelRequest(
                rootRequest.requestId(),
                systemPrompt,
                "Brief: " + rootRequest.objective()
                        + "\nReturn one PlanSpec JSON object."
                        + (repair ? REPAIR_SUFFIX : ""),
                temperature,
                (int) Math.min(maxOutputTokensPerCall,
                        plannerProfile.executionBudget().maxTotalTokens()),
                ModelRequest.OutputFormat.JSON_OBJECT,
                completionTimeout);
        try {
            budget.checkModelCall(Math.min(maxOutputTokensPerCall,
                    plannerProfile.executionBudget().maxTotalTokens()));
        } catch (BudgetTracker.BudgetExceededException ex) {
            return new PlannerAttempt(null, false, false, mapBudgetReason(ex));
        }
        try {
            var completion = modelPort.complete(request, completionTimeout);
            budget.recordModelCall(
                    Math.toIntExact(completion.inputTokens()),
                    Math.toIntExact(completion.outputTokens()));
            return new PlannerAttempt(completion, false, false, null);
        } catch (ModelCallException ex) {
            budget.recordModelCall(0, 0);
            return new PlannerAttempt(
                    null,
                    ex.isRetryable(),
                    ex.isSameProviderRetryable(),
                    null);
        }
    }

    private record PlannerAttempt(ModelCompletion completion, boolean retryable,
                                  boolean sameProviderRetryable,
                                  AgentTerminalReason terminalReason) {
    }

    private static PlanningOutcome outcome(PlanSpec plan, BudgetTracker budget,
                                            AgentTerminalReason reason) {
        var snapshot = budget.snapshot();
        return new PlanningOutcome(plan,
                new AgentExecutionResult.Metrics(
                        snapshot.modelCallsUsed(), 0, snapshot.tokensUsed(), 0),
                reason);
    }

    private static AgentTerminalReason mapBudgetReason(
            BudgetTracker.BudgetExceededException ex) {
        return switch (ex.reason()) {
            case MODEL_CALL_LIMIT -> AgentTerminalReason.MODEL_CALL_LIMIT_EXCEEDED;
            case TOOL_CALL_LIMIT -> AgentTerminalReason.TOOL_CALL_LIMIT_EXCEEDED;
            case TOKEN_LIMIT -> AgentTerminalReason.TOKEN_LIMIT_EXCEEDED;
            case REACT_ROUND_LIMIT -> AgentTerminalReason.REACT_ROUND_LIMIT_EXCEEDED;
            case DEADLINE_EXCEEDED -> AgentTerminalReason.DEADLINE_EXCEEDED;
        };
    }
}
