package com.pulseink.service.memory;

import com.pulseink.domain.memory.CampaignInsight;
import com.pulseink.domain.memory.InsightStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for campaign insights. Decisions only ever transition a PENDING row once
 * (CAS); APPROVED rows move through the derived-index lifecycle while REJECTED rows stay
 * NOT_INDEXED forever.
 */
public interface CampaignInsightRepository {

    Optional<CampaignInsight> findById(long id);

    Optional<CampaignInsight> findBySnapshot(long runId, String snapshotHash,
                                             String promptVersion);

    List<CampaignInsight> findByCampaign(long campaignId);

    CampaignInsight insertPending(CampaignInsight candidate);

    /**
     * CAS transition PENDING to APPROVED or REJECTED in one short transaction. APPROVED also
     * moves the row to INDEX_PENDING with a due next attempt; REJECTED stays NOT_INDEXED.
     * Throws {@link IllegalStateException} when the row no longer matches the expected version
     * or is not PENDING.
     */
    CampaignInsight decidePending(long id, long expectedVersion, InsightStatus targetStatus,
                                  String comment, long actorId, Instant reviewedAt);

    List<CampaignInsight> claimIndexDue(Instant now, int batchSize);

    boolean markIndexed(long id, long expectedVersion, Instant indexedAt);

    boolean markIndexRetry(long id, long expectedVersion, Instant nextAttemptAt, String error);

    boolean markIndexFailed(long id, long expectedVersion, String error);
}
