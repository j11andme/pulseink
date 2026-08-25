package com.pulseink.domain.content;

import java.time.Instant;
import java.util.List;

public record ContentItem(
        long id,
        long runId,
        String taskId,
        int currentVersionNo,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<ContentVersion> versions,
        List<ApprovalRecord> approvals) {

    public ContentItem {
        versions = List.copyOf(versions);
        approvals = List.copyOf(approvals);
    }
}
