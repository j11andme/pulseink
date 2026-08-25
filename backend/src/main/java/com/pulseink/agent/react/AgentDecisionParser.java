package com.pulseink.agent.react;

/**
 * Parses the model's structured output into a typed {@link AgentDecision}. A failure must never
 * produce a partial tool call or artifact.
 */
public interface AgentDecisionParser {

    AgentDecision parse(String modelOutput);
}
