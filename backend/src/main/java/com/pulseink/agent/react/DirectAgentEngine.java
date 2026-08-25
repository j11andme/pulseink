package com.pulseink.agent.react;

import com.pulseink.agent.api.AgentEngine;
import com.pulseink.agent.api.AgentExecutionObserver;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.BudgetTracker;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelCallException;
import com.pulseink.agent.model.ModelCompletion;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.domain.execution.ExecutionMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DIRECT engine: exactly one model call, zero tool calls, no fallback, no repair. Only a FINAL
 * decision carrying exactly one CONTENT_DRAFT artifact succeeds.
 */
public final class DirectAgentEngine implements AgentEngine {

    private static final String SYSTEM_PROMPT = """
            You are PulseInk DIRECT. Follow the structured decision protocol below.
            Return JSON only, without Markdown fences.
            Return exactly this decision shape:
            {"decision":"FINAL","decisionSummary":"...","artifacts":[{"type":"CONTENT_DRAFT","content":{"title":"...","body":"..."},"sourceRefs":[]}]}
            Produce exactly one CONTENT_DRAFT artifact. Do not call tools, replan, or request approval.
            CONTENT_DRAFT content.title and content.body must both be non-blank strings.
            """;
    private static final Duration DEFAULT_COMPLETION_TIMEOUT = Duration.ofMinutes(3);

    private final ModelRouter router;
    private final AgentDecisionParser parser;
    private final Clock clock;
    private final int maxOutputTokensPerCall;
    private final Duration completionTimeout;
    private final Double temperature;

    public DirectAgentEngine(ModelRouter router, AgentDecisionParser parser) {
        this(router, parser, Clock.systemUTC(),
                ReactLoop.DEFAULT_MAX_OUTPUT_TOKENS_PER_CALL,
                DEFAULT_COMPLETION_TIMEOUT);
    }

    public DirectAgentEngine(ModelRouter router, AgentDecisionParser parser, Clock clock) {
        this(router, parser, clock,
                ReactLoop.DEFAULT_MAX_OUTPUT_TOKENS_PER_CALL,
                DEFAULT_COMPLETION_TIMEOUT);
    }

    public DirectAgentEngine(ModelRouter router, AgentDecisionParser parser,
                             Duration completionTimeout) {
        this(router, parser, Clock.systemUTC(),
                ReactLoop.DEFAULT_MAX_OUTPUT_TOKENS_PER_CALL,
                completionTimeout);
    }

    public DirectAgentEngine(ModelRouter router, AgentDecisionParser parser,
                             int maxOutputTokensPerCall, Duration completionTimeout) {
        this(router, parser, Clock.systemUTC(),
                maxOutputTokensPerCall, completionTimeout);
    }

    public DirectAgentEngine(ModelRouter router, AgentDecisionParser parser,
                             Clock clock, Duration completionTimeout) {
        this(router, parser, clock,
                ReactLoop.DEFAULT_MAX_OUTPUT_TOKENS_PER_CALL,
                completionTimeout);
    }

    public DirectAgentEngine(ModelRouter router, AgentDecisionParser parser,
                             Clock clock, int maxOutputTokensPerCall,
                             Duration completionTimeout) {
        this(router, parser, clock, maxOutputTokensPerCall, completionTimeout, null);
    }

    public DirectAgentEngine(ModelRouter router, AgentDecisionParser parser,
                             Clock clock, int maxOutputTokensPerCall,
                             Duration completionTimeout, Double temperature) {
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.parser = Objects.requireNonNull(parser, "parser must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maxOutputTokensPerCall <= 0) {
            throw new IllegalArgumentException(
                    "maxOutputTokensPerCall must be positive");
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

    @Override
    public ExecutionMode supportedMode() {
        return ExecutionMode.DIRECT;
    }

    @Override
    public AgentExecutionResult execute(
            AgentExecutionRequest request,
            AgentExecutionObserver observer) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        if (request.mode() != ExecutionMode.DIRECT) {
            throw new IllegalArgumentException(
                    "direct engine only supports DIRECT requests");
        }
        if (!request.priorArtifacts().isEmpty()) {
            throw new IllegalArgumentException(
                    "direct engine does not resume prior artifacts");
        }
        var profile = request.profile();
        var budget = new BudgetTracker(
                budgetFor(profile),
                clock,
                request.budgetSnapshot());

        AgentTerminalReason reason;
        List<AgentArtifact> artifacts = List.of();
        int modelCalls = 0;
        long tokens = 0;
        try {
            request.guard().assertCanProceed();
            var route = router.route(profile, Set.of());
            long remainingTokens = Math.max(1,
                    budget.budget().maxTotalTokens() - budget.tokensUsed());
            long maxTokens = Math.min(maxOutputTokensPerCall, remainingTokens);
            budget.checkModelCall((int) Math.min(maxTokens, Integer.MAX_VALUE));
            ModelCompletion completion;
            try {
                completion = route.modelPort().complete(
                        toRequest(request, maxTokens), completionTimeout);
            } catch (ModelCallException ex) {
                budget.recordModelCall(0, 0);
                emitFailed(observer, request, AgentTerminalReason.MODEL_FAILURE,
                        "MODEL_" + ex.failureKind().name());
                return result(request, profile, budget,
                        AgentTerminalReason.MODEL_FAILURE, List.of(), 1, 0);
            }
            budget.recordModelCall(
                    (int) completion.inputTokens(), (int) completion.outputTokens());
            modelCalls = 1;
            tokens = completion.inputTokens() + completion.outputTokens();

            AgentDecision decision;
            try {
                decision = parser.parse(completion.content());
            } catch (IllegalArgumentException ex) {
                emitFailed(observer, request, AgentTerminalReason.INVALID_MODEL_OUTPUT,
                        "DECISION_PARSE_FAILED:" + safeDiagnostic(ex.getMessage()));
                return result(request, profile, budget,
                        AgentTerminalReason.INVALID_MODEL_OUTPUT, List.of(),
                        modelCalls, tokens);
            }
            observer.onEvent(new AgentRuntimeEvent.DecisionRecorded(
                    request.runId(), clock.instant(), decisionType(decision),
                    decision.decisionSummary()));
            if (decision instanceof AgentDecision.FinalDecision finalDecision
                    && isExactlyOneContentDraft(finalDecision)) {
                var spec = finalDecision.artifacts().get(0);
                var artifact = AgentArtifact.create(
                        artifactId(request, spec.type(), 1),
                        request.runId(),
                        AgentArtifact.UNIFIED_TASK_ID,
                        spec.type(),
                        1,
                        spec.content(),
                        spec.sourceRefs(),
                        clock.instant());
                observer.onEvent(new AgentRuntimeEvent.ArtifactCompleted(
                        request.runId(), clock.instant(), artifact,
                        budget.snapshot(), budget.reactRoundsUsed()));
                return result(request, profile, budget,
                        AgentTerminalReason.SUCCEEDED, List.of(artifact),
                        modelCalls, tokens);
            }
            emitFailed(observer, request, AgentTerminalReason.INVALID_MODEL_OUTPUT,
                    "ARTIFACT_VALIDATION_FAILED");
            return result(request, profile, budget,
                    AgentTerminalReason.INVALID_MODEL_OUTPUT, List.of(),
                    modelCalls, tokens);
        } catch (BudgetTracker.BudgetExceededException ex) {
            reason = mapBudgetReason(ex);
            emitFailed(observer, request, reason);
            return result(request, profile, budget, reason, List.of(), modelCalls, tokens);
        }
    }

    private static boolean isExactlyOneContentDraft(
            AgentDecision.FinalDecision decision) {
        return decision.artifacts().size() == 1
                && decision.artifacts().get(0).type() == ArtifactType.CONTENT_DRAFT;
    }

    private ModelRequest toRequest(AgentExecutionRequest request, long maxTokens) {
        return new ModelRequest(
                request.requestId(),
                SYSTEM_PROMPT,
                "Objective: " + request.objective()
                        + "\nRespond with the structured decision protocol.",
                temperature,
                (int) Math.min(maxTokens, Integer.MAX_VALUE),
                ModelRequest.OutputFormat.JSON_OBJECT,
                completionTimeout);
    }

    private static String artifactId(
            AgentExecutionRequest request, ArtifactType type, int version) {
        return request.requestId() + "-" + type.name() + "-" + version;
    }

    private static String decisionType(AgentDecision decision) {
        return switch (decision) {
            case AgentDecision.ToolCallDecision ignored -> "TOOL_CALL";
            case AgentDecision.FinalDecision ignored -> "FINAL";
            case AgentDecision.ReplanDecision ignored -> "REPLAN";
            case AgentDecision.NeedApprovalDecision ignored -> "NEED_APPROVAL";
        };
    }

    private static AgentExecutionResult result(
            AgentExecutionRequest request,
            AgentProfile profile,
            BudgetTracker budget,
            AgentTerminalReason reason,
            List<AgentArtifact> artifacts,
            int modelCalls,
            long tokens) {
        return new AgentExecutionResult(
                request.runId(),
                ExecutionMode.DIRECT,
                artifacts,
                budget.snapshot(),
                reason,
                new AgentExecutionResult.Metrics(modelCalls, 0, tokens, 0));
    }

    private static ExecutionBudget budgetFor(AgentProfile profile) {
        var configured = profile.executionBudget();
        if (configured != null) {
            return configured;
        }
        return ExecutionBudget.defaultDirect(
                Instant.now().plus(Duration.ofMinutes(30)));
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

    private void emitFailed(
            AgentExecutionObserver observer,
            AgentExecutionRequest request,
            AgentTerminalReason reason) {
        observer.onEvent(new AgentRuntimeEvent.RuntimeFailed(
                request.runId(), clock.instant(), reason, reason.name()));
    }

    private void emitFailed(AgentExecutionObserver observer,
                            AgentExecutionRequest request,
                            AgentTerminalReason reason,
                            String diagnostic) {
        observer.onEvent(new AgentRuntimeEvent.RuntimeFailed(
                request.runId(), clock.instant(), reason,
                diagnostic == null || diagnostic.isBlank() ? reason.name() : diagnostic));
    }

    private static String safeDiagnostic(String value) {
        if (value == null || value.isBlank()) return "INVALID_STRUCTURED_OUTPUT";
        String normalized = value.replaceAll("[\\r\\n]+", " ");
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }
}
