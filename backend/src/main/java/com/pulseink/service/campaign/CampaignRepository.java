package com.pulseink.service.campaign;

import com.pulseink.domain.campaign.Campaign;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignPage;
import java.util.Optional;

public interface CampaignRepository {

    Campaign insert(Campaign draft);

    CampaignPage findPage(int page, int size);

    Optional<Campaign> findById(long campaignId);
}
