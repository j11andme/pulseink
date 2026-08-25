package com.pulseink.domain.content;

import java.time.Instant;

public record ApprovalRecord(
        long id,
        long contentVersionId,
        long actorId,
        String comment,
        Instant createdAt) {
}
