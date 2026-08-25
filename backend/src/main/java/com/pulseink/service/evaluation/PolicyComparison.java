package com.pulseink.service.evaluation;

import com.pulseink.domain.execution.ExecutionPolicy;

public record PolicyComparison(
        boolean comparable,
        double qualityDelta,
        double coordinationOverhead,
        ExecutionPolicy preferredPolicy,
        String reason) {

    public static PolicyComparison comparable(double qualityDelta, double overhead,
                                              ExecutionPolicy preferred) {
        return new PolicyComparison(true, qualityDelta, overhead, preferred, "");
    }

    public static PolicyComparison unscored(String reason) {
        return new PolicyComparison(false, 0, 0, null, reason);
    }
}
