package com.pulseink.service.memory;

import com.pulseink.domain.memory.CampaignInsight;

public interface ReviewInsightUseCase {

    CampaignInsight decide(long insightId, InsightDecision decision,
                           String comment, long actorId);
}
