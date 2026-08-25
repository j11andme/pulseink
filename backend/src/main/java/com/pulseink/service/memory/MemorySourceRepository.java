package com.pulseink.service.memory;

/**
 * Read-only projection of the existing content/approval/publication/metric tables into the
 * Campaign Episodic Memory shape. Never duplicates storage: every call joins the authoritative
 * V4/V5 rows.
 */
public interface MemorySourceRepository {

    /**
     * Loads the run's approved exact versions, successful publication receipts and metric rows
     * as one deterministic snapshot. Throws INSIGHT_SOURCE_NOT_READY when any of the three
     * fact groups is missing for the run.
     */
    InsightSourceSnapshot loadEligibleSnapshot(long runId);

    /**
     * Context-facing episodic projection: the same facts as the snapshot but never throwing;
     * missing groups simply render as empty lists.
     */
    CampaignEpisodicMemory loadEpisode(long runId);
}
