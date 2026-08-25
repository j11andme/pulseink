package com.pulseink.service.publishing;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.publication.Publication;

/**
 * Idempotent publishing entry point. Only the exact, currently approved ContentVersion of a
 * run in WAITING_APPROVAL/PUBLISHING/COMPLETED can be published; a replay returns the original
 * publication with its original idempotency key.
 */
public interface PublishContentUseCase {

    Publication publish(Command command);

    record Command(
            long contentId,
            long contentVersionId,
            CampaignChannel channel,
            long actorUserId) {
    }
}
