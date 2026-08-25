package com.pulseink.service.memory;

import com.pulseink.domain.memory.CampaignInsight;

/**
 * User-triggered insight consolidation: build the run's episodic snapshot, ask the model once
 * (outside any transaction) and store exactly one PENDING candidate per snapshot. Replays
 * return the existing candidate without another model call.
 */
public interface ConsolidateInsightUseCase {

    CampaignInsight generateCandidate(long runId, long actorId);
}
