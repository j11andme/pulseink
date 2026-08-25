package com.pulseink.service.campaign;

import com.pulseink.agent.api.AgentExecutionResult;

public interface RunExecutionUseCase {

    void launch(long runId);

    AgentExecutionResult execute(long runId);
}
