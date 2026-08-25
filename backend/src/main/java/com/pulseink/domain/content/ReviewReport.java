package com.pulseink.domain.content;

import com.pulseink.agent.artifact.ArtifactStatus;
import java.time.Instant;
import java.util.List;

public record ReviewReport(
        long id,
        long runId,
        String sourceArtifactId,
        int sourceArtifactVersion,
        ArtifactStatus sourceArtifactStatus,
        boolean passed,
        int repairRound,
        List<ReviewIssue> issues,
        Instant createdAt) {

    public ReviewReport {
        issues = List.copyOf(issues);
    }
}
