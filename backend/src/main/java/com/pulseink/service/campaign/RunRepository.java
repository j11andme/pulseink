package com.pulseink.service.campaign;

import com.pulseink.domain.campaign.CampaignRun;
import java.util.List;
import java.util.Optional;

public interface RunRepository {

    CampaignRun insert(CampaignRun run);

    Optional<CampaignRun> findById(long runId);

    List<CampaignRun> findByCampaignId(long campaignId);

    /**
     * Optimistic-lock update: the row must still carry {@code run.version()}; otherwise the run
     * was modified concurrently and the update fails loudly.
     */
    void update(CampaignRun run);
}
