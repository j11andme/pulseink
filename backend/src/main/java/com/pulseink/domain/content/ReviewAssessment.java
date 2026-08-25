package com.pulseink.domain.content;

import java.util.List;

public record ReviewAssessment(boolean passed, List<ReviewIssue> issues) {

    public ReviewAssessment {
        if (issues == null) {
            throw new IllegalArgumentException("review issues must not be null");
        }
        issues = List.copyOf(issues);
        if (passed && !issues.isEmpty()) {
            throw new IllegalArgumentException("passed review must not contain issues");
        }
        if (!passed && (issues.isEmpty() || issues.size() > 20)) {
            throw new IllegalArgumentException("failed review must contain between 1 and 20 issues");
        }
    }
}
