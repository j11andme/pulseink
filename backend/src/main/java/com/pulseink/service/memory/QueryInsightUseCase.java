package com.pulseink.service.memory;

import com.pulseink.domain.memory.CampaignInsight;
import java.util.List;

public interface QueryInsightUseCase {

    List<CampaignInsight> listByCampaign(long campaignId);

    List<ApprovedInsightHit> searchApproved(String query,
                                            com.pulseink.domain.campaign.CampaignChannel channel,
                                            int topK);
}
