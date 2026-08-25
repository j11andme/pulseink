package com.pulseink.agent.selection;

import com.pulseink.domain.execution.ExecutionDecision;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.execution.TaskProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RuleBasedExecutionModeSelector implements ExecutionModeSelector {

    public static final String POLICY_VERSION = "selector-v1";

    static final String REASON_MANUAL_POLICY_OVERRIDE = "MANUAL_POLICY_OVERRIDE";
    static final String REASON_LOW_RISK_SINGLE_OUTPUT = "LOW_RISK_SINGLE_OUTPUT";
    static final String REASON_DECOMPOSABLE_OR_HIGH_RISK = "DECOMPOSABLE_OR_HIGH_RISK";
    static final String REASON_UNIFIED_CONTEXT_PREFERRED = "UNIFIED_CONTEXT_PREFERRED";

    private final SelectorPolicy policy;

    public RuleBasedExecutionModeSelector() {
        this(SelectorPolicy.V1);
    }

    public RuleBasedExecutionModeSelector(SelectorPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "selector policy must not be null");
    }

    @Override
    public ExecutionDecision select(ExecutionPolicy requested, TaskProperties properties) {
        Objects.requireNonNull(requested, "requested policy must not be null");
        Objects.requireNonNull(properties, "task properties must not be null");
        if (requested != ExecutionPolicy.ADAPTIVE) {
            return decision(toMode(requested), properties, REASON_MANUAL_POLICY_OVERRIDE);
        }
        if (properties.channelCount() == 1
                && properties.sourceDiversity() == 0
                && properties.factualRisk() < 0.3
                && properties.toolBreadth() == 0) {
            return decision(ExecutionMode.DIRECT, properties, REASON_LOW_RISK_SINGLE_OUTPUT);
        }
        if (properties.parallelResearchBranches() >= 2
                || properties.channelCount() >= 2
                || properties.factualRisk() >= 0.7) {
            return decision(
                    ExecutionMode.ORCHESTRATED,
                    properties,
                    REASON_DECOMPOSABLE_OR_HIGH_RISK);
        }
        return decision(ExecutionMode.REACT, properties, REASON_UNIFIED_CONTEXT_PREFERRED);
    }

    private ExecutionDecision decision(
            ExecutionMode mode, TaskProperties properties, String reasonCode) {
        return new ExecutionDecision(
                mode,
                policy.version(),
                List.of(reasonCode),
                featureSnapshot(properties),
                estimatedTokenBudget(properties));
    }

    private long estimatedTokenBudget(TaskProperties properties) {
        return Math.min(properties.latencyBudgetMs(), policy.maxTokenBudget());
    }

    private static Map<String, Object> featureSnapshot(TaskProperties properties) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("decomposability", properties.decomposability());
        snapshot.put("channelCount", properties.channelCount());
        snapshot.put("sourceDiversity", properties.sourceDiversity());
        snapshot.put("parallelResearchBranches", properties.parallelResearchBranches());
        snapshot.put("sequentialDependency", properties.sequentialDependency());
        snapshot.put("factualRisk", properties.factualRisk());
        snapshot.put("toolBreadth", properties.toolBreadth());
        snapshot.put("latencyBudgetMs", properties.latencyBudgetMs());
        return Collections.unmodifiableMap(snapshot);
    }

    private static ExecutionMode toMode(ExecutionPolicy requested) {
        return switch (requested) {
            case DIRECT -> ExecutionMode.DIRECT;
            case REACT -> ExecutionMode.REACT;
            case ORCHESTRATED -> ExecutionMode.ORCHESTRATED;
            case ADAPTIVE -> throw new IllegalArgumentException(
                    "adaptive policy requires rule evaluation");
        };
    }
}
