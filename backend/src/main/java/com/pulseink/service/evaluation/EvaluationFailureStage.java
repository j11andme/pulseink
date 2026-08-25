package com.pulseink.service.evaluation;

/** Auditable stage attribution; it describes observable failures, never hidden reasoning. */
public enum EvaluationFailureStage {
    NONE,
    HARNESS,
    MODEL_PROVIDER,
    PLANNER,
    TOOL_SELECTION,
    TOOL_ARGUMENT,
    TOOL_EXECUTION,
    ARTIFACT_SCHEMA,
    EVIDENCE,
    BUDGET,
    TASK_COMPLETION,
    JUDGE
}
