package com.pulseink.agent.api;

/**
 * The current instance lost the run lease: it must stop launching new model/tool calls and
 * stop persisting checkpoints or terminal states, and the loss must propagate to the
 * RunExecutionService instead of being swallowed as an ordinary runtime failure.
 */
public final class ExecutionOwnershipLostException extends RuntimeException {

    private final long runId;

    public ExecutionOwnershipLostException(long runId) {
        super("execution ownership was lost for run " + runId);
        this.runId = runId;
    }

    public long runId() {
        return runId;
    }
}
