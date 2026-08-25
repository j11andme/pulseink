package com.pulseink.service.memory;

import java.util.List;

/**
 * Read-only Campaign Episodic Memory projection: the existing approved versions, publication
 * receipts and metric rows of one run. Never a second storage of facts.
 */
public record CampaignEpisodicMemory(
        long campaignId,
        long runId,
        List<InsightSourceSnapshot.ApprovedVersion> approvedVersions,
        List<InsightSourceSnapshot.PublishedPost> publications,
        List<InsightSourceSnapshot.MetricWindow> metrics) {

    public CampaignEpisodicMemory {
        approvedVersions = List.copyOf(approvedVersions);
        publications = List.copyOf(publications);
        metrics = List.copyOf(metrics);
    }

    public static CampaignEpisodicMemory empty(long runId) {
        return new CampaignEpisodicMemory(0L, runId, List.of(), List.of(), List.of());
    }
}
