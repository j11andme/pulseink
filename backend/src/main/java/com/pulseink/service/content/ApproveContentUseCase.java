package com.pulseink.service.content;

import com.pulseink.domain.content.ApprovalRecord;

public interface ApproveContentUseCase {

    ApprovalRecord approve(Command command);

    record Command(long contentId, long contentVersionId,
                   int expectedCurrentVersionNo, long expectedItemVersion,
                   String comment, long actorUserId) {
    }
}
