package com.pulseink.agent.plan;

/**
 * Strict structured plan parser. Unknown fields are rejected, never silently ignored.
 */
public interface PlanParser {

    PlanSpec parse(String modelOutput);
}
