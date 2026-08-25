package com.pulseink.service.content;

import com.pulseink.agent.api.AgentExecutionResult;

@FunctionalInterface
public interface CaptureRunContentUseCase {
    void capture(long runId, AgentExecutionResult result);
}
