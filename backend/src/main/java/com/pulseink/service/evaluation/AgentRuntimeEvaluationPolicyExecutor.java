package com.pulseink.service.evaluation;

import com.pulseink.agent.api.AgentEngine;
import com.pulseink.agent.api.AgentExecutionRequest;
import com.pulseink.agent.api.AgentRuntimeEvent;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.selection.ExecutionModeSelector;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Executes evaluation policies through the existing governed engines without creating business rows. */
public final class AgentRuntimeEvaluationPolicyExecutor implements EvaluationPolicyExecutor {

    private static final String EVALUATION_SYSTEM_PROMPT = """
            You are PulseInk Evaluation Content Agent. Complete the supplied campaign brief.
            When a governed knowledge tool is available and facts need evidence, use it only until
            evidence is sufficient, then return FINAL with exactly one CONTENT_DRAFT. Preserve
            evidence sourceId values in sourceRefs. Successful content automatically enters human
            approval, so never return NEED_APPROVAL. Never repeat an identical tool call.
            """;

    private final ExecutionModeSelector selector;
    private final EnumMap<ExecutionMode, AgentEngine> engines = new EnumMap<>(ExecutionMode.class);
    private final ModelPolicy modelPolicy;
    private final EvaluationScenarioContext scenarioContext;
    private final Clock clock;
    private final EvaluationRuntimeDescriptor runtimeDescriptor;
    private final AtomicLong runIds = new AtomicLong(8_000_000);

    public AgentRuntimeEvaluationPolicyExecutor(ExecutionModeSelector selector,
                                                List<AgentEngine> engines,
                                                ModelPolicy modelPolicy,
                                                EvaluationScenarioContext scenarioContext,
                                                Clock clock) {
        this(selector, engines, modelPolicy, scenarioContext, clock,
                new EvaluationRuntimeDescriptor(
                        modelPolicy.providerIds().getFirst(),
                        modelPolicy.providerIds().getFirst(),
                        "fake".equals(modelPolicy.providerIds().getFirst())));
    }

    public AgentRuntimeEvaluationPolicyExecutor(ExecutionModeSelector selector,
                                                List<AgentEngine> engines,
                                                ModelPolicy modelPolicy,
                                                EvaluationScenarioContext scenarioContext,
                                                Clock clock,
                                                EvaluationRuntimeDescriptor runtimeDescriptor) {
        this.selector = Objects.requireNonNull(selector);
        for (var engine : engines) {
            if (this.engines.put(engine.supportedMode(), engine) != null) {
                throw new IllegalArgumentException("duplicate engine: " + engine.supportedMode());
            }
        }
        if (this.engines.size() != ExecutionMode.values().length) {
            throw new IllegalArgumentException("evaluation requires all three execution engines");
        }
        this.modelPolicy = Objects.requireNonNull(modelPolicy);
        this.scenarioContext = Objects.requireNonNull(scenarioContext);
        this.clock = Objects.requireNonNull(clock);
        this.runtimeDescriptor = Objects.requireNonNull(runtimeDescriptor);
    }

    @Override
    public EvaluationRuntimeDescriptor runtimeDescriptor() {
        return runtimeDescriptor;
    }

    @Override
    public synchronized EvaluationExecution execute(EvaluationCase testCase, ExecutionPolicy policy) {
        var decision = selector.select(policy, testCase.taskProperties());
        ExecutionMode mode = decision.selectedMode();
        long runId = runIds.incrementAndGet();
        Instant deadline = Instant.now().plusSeconds(300);
        var budget = new ExecutionBudget(
                maxModelCalls(mode),
                EvaluationBudgetLimits.TOOL_CALLS,
                EvaluationBudgetLimits.TOTAL_TOKENS,
                maxReactRounds(mode),
                1,
                deadline);
        var profile = AgentProfile.unified("evaluation-" + policy.name().toLowerCase(),
                testCase.allowedTools(), modelPolicy, budget,
                EVALUATION_SYSTEM_PROMPT, Set.of(ArtifactType.CONTENT_DRAFT));
        var events = new java.util.concurrent.CopyOnWriteArrayList<AgentRuntimeEvent>();
        var request = new AgentExecutionRequest(
                runId, "eval-" + testCase.caseId() + "-" + policy.name().toLowerCase(),
                mode, profile, objective(testCase), List.of(), BudgetSnapshot.ZERO,
                ApprovalState.NOT_REQUIRED, "unified", testCase.campaignInput().channels());

        long started = System.nanoTime();
        scenarioContext.activate(testCase);
        try {
            var result = engines.get(mode).execute(request, events::add);
            long latencyMs = elapsedMs(started);
            var toolTrace = toolTrace(events);
            var retrieved = toolTrace.stream().flatMap(call -> call.sourceRefs().stream())
                    .distinct().toList();
            var refs = result.artifacts().stream().flatMap(artifact -> artifact.sourceRefs().stream())
                    .distinct().toList();
            var tools = toolTrace.stream().map(EvaluationToolCall::qualifiedName)
                    .collect(Collectors.toUnmodifiableSet());
            int repairs = (int) events.stream()
                    .filter(AgentRuntimeEvent.RepairRoundStarted.class::isInstance).count();
            int coordinationArtifacts = mode == ExecutionMode.ORCHESTRATED
                    ? result.artifacts().size() : 0;
            var outcome = terminal(result.terminalReason());
            String candidate = result.artifacts().stream()
                    .filter(artifact -> artifact.type() == ArtifactType.CONTENT_DRAFT)
                    .map(artifact -> artifact.content().toString())
                    .collect(Collectors.joining("\n"));
            return new EvaluationExecution(
                    testCase.caseId(), policy, mode, outcome.finalState(), outcome.reason(),
                    retrieved, refs, tools, result.metrics().modelCalls(),
                    result.metrics().toolCalls(), result.metrics().totalTokens(),
                    result.metrics().reactRounds(), repairs, coordinationArtifacts,
                    latencyMs, candidate, toolTrace, trace(events));
        } catch (RuntimeException unexpected) {
            long latencyMs = elapsedMs(started);
            var trace = new ArrayList<>(trace(events));
            trace.add(new EvaluationTraceStep(trace.size() + 1, clock.instant(),
                    "HARNESS_ERROR", "evaluation", testCase.caseId(), "ERROR",
                    "Evaluation runtime raised an unexpected "
                            + unexpected.getClass().getSimpleName(), List.of()));
            return new EvaluationExecution(
                    testCase.caseId(), policy, mode, "FAILED",
                    AgentTerminalReason.RUNTIME_FAILED, List.of(), List.of(), Set.of(),
                    0, 0, 0, 0, 0, 0, latencyMs, "", List.of(), trace);
        } finally {
            scenarioContext.clear();
        }
    }

    private static Terminal terminal(AgentTerminalReason reason) {
        if (reason == AgentTerminalReason.SUCCEEDED
                || reason == AgentTerminalReason.APPROVAL_REQUIRED) {
            return new Terminal("WAITING_APPROVAL", reason);
        }
        if (reason == AgentTerminalReason.HUMAN_INTERVENTION_REQUIRED) {
            return new Terminal("WAITING_HUMAN", reason);
        }
        return new Terminal("FAILED", reason);
    }

    private static long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private static List<EvaluationToolCall> toolTrace(List<AgentRuntimeEvent> events) {
        var calls = new ArrayList<MutableToolCall>();
        int sequence = 0;
        for (var event : events) {
            if (event instanceof AgentRuntimeEvent.ToolCallStarted started) {
                calls.add(new MutableToolCall(++sequence, started.qualifiedName(),
                        sanitizedArguments(started.arguments()), "STARTED", List.of()));
            } else if (event instanceof AgentRuntimeEvent.ToolCallCompleted completed) {
                for (int index = calls.size() - 1; index >= 0; index--) {
                    var call = calls.get(index);
                    if (call.qualifiedName.equals(completed.qualifiedName())
                            && call.outcome.equals("STARTED")) {
                        call.outcome = "TOOL_FAILURE".equals(completed.observationSummary())
                                ? "FAILED" : "SUCCEEDED";
                        call.sourceRefs = sourceRefs(completed.metadata());
                        break;
                    }
                }
            }
        }
        return calls.stream().map(MutableToolCall::snapshot).toList();
    }

    private static Map<String, String> sanitizedArguments(Map<String, Object> arguments) {
        var result = new LinkedHashMap<String, String>();
        arguments.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String value = String.valueOf(entry.getValue());
            result.put(entry.getKey(), value.length() > 256 ? value.substring(0, 256) : value);
        });
        return Map.copyOf(result);
    }

    private static List<String> sourceRefs(Map<String, String> metadata) {
        String encoded = metadata.get("sourceRefs");
        if (encoded == null || encoded.isBlank()) return List.of();
        return java.util.Arrays.stream(encoded.split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static List<EvaluationTraceStep> trace(List<AgentRuntimeEvent> events) {
        var result = new ArrayList<EvaluationTraceStep>();
        for (var event : events) {
            int sequence = result.size() + 1;
            result.add(switch (event) {
                case AgentRuntimeEvent.DecisionRecorded value -> step(sequence, value.timestamp(),
                        "DECISION", "agent", value.decisionType(), "OBSERVED",
                        value.decisionSummary(), List.of());
                case AgentRuntimeEvent.ToolCallStarted value -> step(sequence, value.timestamp(),
                        "TOOL_CALL", "agent", value.qualifiedName(), "STARTED",
                        "argumentKeys=" + value.arguments().keySet().stream().sorted().toList(),
                        List.of());
                case AgentRuntimeEvent.ToolCallCompleted value -> step(sequence, value.timestamp(),
                        "TOOL_RESULT", "tool", value.qualifiedName(),
                        "TOOL_FAILURE".equals(value.observationSummary()) ? "FAILED" : "SUCCEEDED",
                        value.observationSummary(), sourceRefs(value.metadata()));
                case AgentRuntimeEvent.ArtifactCompleted value -> step(sequence, value.timestamp(),
                        "ARTIFACT", value.artifact().taskId(), value.artifact().type().name(),
                        value.artifact().status().name(), "artifact completed",
                        value.artifact().sourceRefs());
                case AgentRuntimeEvent.RuntimeFailed value -> step(sequence, value.timestamp(),
                        "RUNTIME", "runtime", value.reason().name(), "FAILED",
                        value.message(), List.of());
                case AgentRuntimeEvent.TaskStarted value -> step(sequence, value.timestamp(),
                        "TASK", value.role().name(), value.taskId(), "STARTED", "", List.of());
                case AgentRuntimeEvent.TaskCompleted value -> step(sequence, value.timestamp(),
                        "TASK", value.role().name(), value.taskId(), "COMPLETED", "", List.of());
                case AgentRuntimeEvent.ReviewIssueCreated value -> step(sequence, value.timestamp(),
                        "REVIEW", "reviewer", value.issueType().name(), "ISSUE",
                        "repairRound=" + value.repairRound(), value.affectedTaskIds().stream().sorted().toList());
                case AgentRuntimeEvent.RepairRoundStarted value -> step(sequence, value.timestamp(),
                        "REPAIR", "coordinator", value.path().name(), "STARTED",
                        "repairRound=" + value.repairRound(), value.rootTaskIds().stream().sorted().toList());
                case AgentRuntimeEvent.ArtifactInvalidated value -> step(sequence, value.timestamp(),
                        "ARTIFACT", value.artifact().taskId(), value.artifact().type().name(),
                        "INVALIDATED", "repairRound=" + value.repairRound(), value.artifact().sourceRefs());
                case AgentRuntimeEvent.RepairExhausted value -> step(sequence, value.timestamp(),
                        "REPAIR", "coordinator", "repair-budget", "EXHAUSTED",
                        "completedRounds=" + value.completedRepairRounds(), List.of());
            });
        }
        return List.copyOf(result);
    }

    private static EvaluationTraceStep step(int sequence, Instant timestamp, String type,
                                            String actor, String subject, String outcome,
                                            String summary, List<String> evidence) {
        String safe = summary == null ? "" : summary;
        if (safe.length() > 500) safe = safe.substring(0, 500);
        return new EvaluationTraceStep(sequence, timestamp, type, actor, subject,
                outcome, safe, evidence);
    }

    private static int maxModelCalls(ExecutionMode mode) {
        return switch (mode) {
            case DIRECT -> EvaluationBudgetLimits.DIRECT_MODEL_CALLS;
            case REACT -> EvaluationBudgetLimits.REACT_MODEL_CALLS;
            case ORCHESTRATED -> EvaluationBudgetLimits.ORCHESTRATED_MODEL_CALLS;
        };
    }

    private static int maxReactRounds(ExecutionMode mode) {
        return switch (mode) {
            case DIRECT -> EvaluationBudgetLimits.DIRECT_REACT_ROUNDS;
            case REACT -> EvaluationBudgetLimits.REACT_ROUNDS;
            case ORCHESTRATED -> EvaluationBudgetLimits.ORCHESTRATED_REACT_ROUNDS;
        };
    }

    private static String objective(EvaluationCase testCase) {
        var input = testCase.campaignInput();
        return "Evaluation case: " + testCase.caseId()
                + "\nGoal: " + input.goal()
                + "\nAudience: " + input.audience()
                + "\nChannels: " + input.channels()
                + "\nConstraints: " + input.constraints();
    }

    private record Terminal(String finalState, AgentTerminalReason reason) {}

    private static final class MutableToolCall {
        private final int sequence;
        private final String qualifiedName;
        private final Map<String, String> arguments;
        private String outcome;
        private List<String> sourceRefs;

        private MutableToolCall(int sequence, String qualifiedName,
                                Map<String, String> arguments, String outcome,
                                List<String> sourceRefs) {
            this.sequence = sequence;
            this.qualifiedName = qualifiedName;
            this.arguments = arguments;
            this.outcome = outcome;
            this.sourceRefs = sourceRefs;
        }

        private EvaluationToolCall snapshot() {
            return new EvaluationToolCall(sequence, qualifiedName, arguments, outcome, sourceRefs);
        }
    }
}
