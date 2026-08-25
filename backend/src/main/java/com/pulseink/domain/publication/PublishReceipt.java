package com.pulseink.domain.publication;

import com.pulseink.domain.campaign.CampaignChannel;
import java.time.Instant;
import java.util.UUID;

/**
 * Receipt returned by the Channel Sandbox for one idempotent publish call. {@code replayed} is
 * true when the sandbox recognized the idempotency key and returned the previously created post.
 */
public record PublishReceipt(
        UUID externalPostId,
        UUID idempotencyKey,
        CampaignChannel channel,
        Instant publishedAt,
        boolean replayed) {
}
