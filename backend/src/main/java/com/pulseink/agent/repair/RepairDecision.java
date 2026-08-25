package com.pulseink.agent.repair;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

public record RepairDecision(
        RepairPath path,
        Set<String> rootTaskIds,
        Set<String> invalidatedTaskIds,
        boolean requiresReplan,
        boolean requiresHuman,
        int nextRepairRound) {

    public RepairDecision {
        if (path == null || rootTaskIds == null || invalidatedTaskIds == null) {
            throw new IllegalArgumentException("repair decision fields must not be null");
        }
        rootTaskIds = Collections.unmodifiableSet(new TreeSet<>(rootTaskIds));
        invalidatedTaskIds = Collections.unmodifiableSet(new TreeSet<>(invalidatedTaskIds));
        if (nextRepairRound < 0) {
            throw new IllegalArgumentException("nextRepairRound must not be negative");
        }
    }
}
