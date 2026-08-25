package com.pulseink.service.campaign;

import com.pulseink.domain.campaign.Campaign;
import java.util.List;

public interface QueryCampaignUseCase {

    CampaignPage list(int page, int size);

    Campaign get(long campaignId);

    record CampaignPage(
            List<Campaign> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    final class CampaignNotFoundException extends RuntimeException {
        public CampaignNotFoundException(long campaignId) {
            super("campaign " + campaignId + " was not found");
        }
    }
}
