package com.pulseink.agent.orchestration;

import com.pulseink.agent.api.AgentExecutionResult;
import com.pulseink.agent.api.AgentTerminalReason;
import com.pulseink.agent.plan.PlanSpec;

/**
 * Planner outcome: the validated plan plus planner metrics and terminal reason.
 */
public record PlanningOutcome(
        PlanSpec plan,
        AgentExecutionResult.Metrics metrics,
        AgentTerminalReason terminalReason) {
}
