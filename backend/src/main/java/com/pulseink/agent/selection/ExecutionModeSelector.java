package com.pulseink.agent.selection;

import com.pulseink.domain.execution.ExecutionDecision;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.execution.TaskProperties;

public interface ExecutionModeSelector {

    ExecutionDecision select(ExecutionPolicy requested, TaskProperties properties);
}
