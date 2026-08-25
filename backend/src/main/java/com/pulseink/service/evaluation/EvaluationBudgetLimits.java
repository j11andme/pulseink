package com.pulseink.service.evaluation;

/** Shared system-level limits used by both policy execution and deterministic scoring. */
public final class EvaluationBudgetLimits {

    public static final int DIRECT_MODEL_CALLS = 1;
    public static final int DIRECT_REACT_ROUNDS = 1;
    public static final int REACT_MODEL_CALLS = 16;
    public static final int REACT_ROUNDS = 8;
    public static final int ORCHESTRATED_MODEL_CALLS = 64;
    public static final int ORCHESTRATED_REACT_ROUNDS = 64;
    public static final int TOOL_CALLS = 24;
    public static final long TOTAL_TOKENS = 256_000;

    private EvaluationBudgetLimits() {}
}
