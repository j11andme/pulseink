package com.pulseink.agent.plan;

import java.util.List;
import java.util.Objects;

/**
 * Immutable validated plan DAG.
 */
public record PlanSpec(
        int schemaVersion,
        List<PlanTask> tasks) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public PlanSpec {
        tasks = List.copyOf(Objects.requireNonNull(tasks, "tasks must not be null"));
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported plan schemaVersion: " + schemaVersion);
        }
    }
}
