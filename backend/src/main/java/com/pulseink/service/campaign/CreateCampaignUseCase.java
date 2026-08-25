package com.pulseink.service.campaign;

import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignChannel;
import java.util.List;

public interface CreateCampaignUseCase {

    Campaign create(CreateCampaignCommand command, long actorUserId);

    record CreateCampaignCommand(
            String name,
            String objective,
            String audience,
            List<CampaignChannel> channels,
            List<String> constraints) {
    }
}
