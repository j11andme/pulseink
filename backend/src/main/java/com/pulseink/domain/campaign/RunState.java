package com.pulseink.domain.campaign;

public enum RunState {
    CREATED,
    PLANNING,
    RUNNING,
    REPLANNING,
    PAUSED,
    WAITING_HUMAN,
    WAITING_APPROVAL,
    PUBLISHING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
