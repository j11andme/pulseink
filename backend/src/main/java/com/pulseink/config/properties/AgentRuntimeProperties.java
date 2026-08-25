package com.pulseink.config.properties;

import com.pulseink.agent.react.ReactLoop;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Completion boundary shared by Planner and role ReAct calls.
 */
@ConfigurationProperties("pulseink.agent-runtime")
public record AgentRuntimeProperties(
        int maxOutputTokensPerCall,
        Duration completionTimeout) {

    public AgentRuntimeProperties {
        if (maxOutputTokensPerCall < 0) {
            throw new IllegalArgumentException(
                    "agent runtime max output tokens must not be negative");
        }
        if (maxOutputTokensPerCall == 0) {
            maxOutputTokensPerCall = ReactLoop.DEFAULT_MAX_OUTPUT_TOKENS_PER_CALL;
        }
        if (completionTimeout == null) {
            completionTimeout = ReactLoop.DEFAULT_COMPLETION_TIMEOUT;
        }
        if (completionTimeout.isZero() || completionTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "agent runtime completion timeout must be positive");
        }
    }
}
