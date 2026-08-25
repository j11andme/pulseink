package com.pulseink.service.memory;

import com.pulseink.domain.campaign.CampaignChannel;
import java.util.List;

/**
 * Agent-facing memory port: MySQL is the authority behind every projection, Redis only caches
 * the working memory and Elasticsearch only serves approved insight search. No repository or
 * client type leaks into the agent layer.
 */
public interface MemoryPort {

    WorkingMemoryResult loadRunWorkingMemory(long runId);

    CampaignEpisodicMemory loadCampaignEpisode(long runId);

    /**
     * Only APPROVED insights can appear here. When the derived search is unavailable this
     * returns an empty list after a sanitized warning: approved insights are context
     * enrichment, never the run's only source of facts.
     */
    List<ApprovedInsightHit> searchApprovedInsights(String query,
                                                    CampaignChannel channel,
                                                    int topK);

    record WorkingMemoryResult(RunWorkingMemory memory, boolean cacheHit) {
    }
}
