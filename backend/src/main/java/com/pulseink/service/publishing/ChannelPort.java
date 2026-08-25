package com.pulseink.service.publishing;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.publication.PublishReceipt;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Channel Sandbox HTTP port. Implementations never run inside a database transaction.
 */
public interface ChannelPort {

    PublishReceipt publish(PublishRequest request);

    record PublishRequest(
            long sourcePublicationId,
            long contentVersionId,
            UUID idempotencyKey,
            CampaignChannel channel,
            Map<String, Object> content,
            List<String> sourceRefs) {
    }
}
