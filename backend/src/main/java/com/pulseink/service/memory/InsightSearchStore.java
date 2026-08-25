package com.pulseink.service.memory;

import com.pulseink.domain.memory.CampaignInsight;
import com.pulseink.service.embedding.EmbeddingProfile;
import java.util.List;

/**
 * Derived Elasticsearch port for APPROVED insights. A PENDING/REJECTED row must never enter
 * the index, even when the adapter is called by mistake.
 */
public interface InsightSearchStore {

    void ensureCompatibleIndex(EmbeddingProfile profile);

    void indexApproved(CampaignInsight insight);

    List<ApprovedInsightHit> search(String query,
                                    com.pulseink.domain.campaign.CampaignChannel channel,
                                    int topK);
}
