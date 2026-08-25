package com.pulseink.service.memory;

import com.pulseink.domain.memory.InsightStatus;

/**
 * Human decision on a PENDING insight candidate. Only an explicit user action can move a
 * candidate toward long-term memory.
 */
public enum InsightDecision {
    APPROVE,
    REJECT;

    public InsightStatus targetStatus() {
        return this == APPROVE ? InsightStatus.APPROVED : InsightStatus.REJECTED;
    }
}
