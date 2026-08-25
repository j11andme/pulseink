package com.pulseink.service.memory;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightScopeType;
import java.time.Instant;
import java.util.List;

/**
 * Search result of an APPROVED long-term insight with its traceable origin and authority
 * labels for context rendering.
 */
public record ApprovedInsightHit(
        long insightId,
        long sourceCampaignId,
        String title,
        String insightText,
        InsightCategory category,
        InsightScopeType scopeType,
        String scopeValue,
        List<CampaignChannel> applicableChannels,
        double confidence,
        Instant approvedAt) {

    public ApprovedInsightHit {
        applicableChannels = List.copyOf(applicableChannels);
    }
}
