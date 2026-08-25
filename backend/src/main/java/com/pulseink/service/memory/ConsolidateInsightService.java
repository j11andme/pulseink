package com.pulseink.service.memory;

import com.pulseink.domain.memory.CampaignInsight;
import java.time.Clock;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consolidation rules: eligible snapshot first, snapshot-key idempotency second, then the
 * model call strictly outside any database transaction, then one short insert transaction.
 * Concurrent replays of the same snapshot converge on the single unique row.
 */
public final class ConsolidateInsightService implements ConsolidateInsightUseCase {

    public static final String PROMPT_VERSION = "insight-v1";

    private final MemorySourceRepository sourceRepository;
    private final CampaignInsightRepository insightRepository;
    private final InsightCandidateGenerator generator;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ConsolidateInsightService(MemorySourceRepository sourceRepository,
                                     CampaignInsightRepository insightRepository,
                                     InsightCandidateGenerator generator,
                                     TransactionTemplate transactions,
                                     Clock clock) {
        this.sourceRepository = Objects.requireNonNull(sourceRepository);
        this.insightRepository = Objects.requireNonNull(insightRepository);
        this.generator = Objects.requireNonNull(generator);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public CampaignInsight generateCandidate(long runId, long actorId) {
        if (runId <= 0 || actorId <= 0) {
            throw new InsightException(InsightErrorCode.VALIDATION_ERROR,
                    "run id and actor id must be positive");
        }
        InsightSourceSnapshot source = sourceRepository.loadEligibleSnapshot(runId);
        var existing = insightRepository.findBySnapshot(
                runId, source.sourceSnapshotHash(), PROMPT_VERSION);
        if (existing.isPresent()) {
            return existing.get();
        }

        GeneratedInsight generated = generator.generate(source);

        var candidate = CampaignInsight.pending(
                source.campaignId(),
                runId,
                generated.category(),
                generated.title(),
                generated.insightText(),
                generated.scopeType(),
                generated.scopeValue(),
                generated.applicableChannels(),
                generated.evidenceRefs(),
                generated.confidence(),
                generated.limitations(),
                source.sourceSnapshotHash(),
                PROMPT_VERSION,
                actorId,
                clock.instant());

        return transactions.execute(status -> {
            var raced = insightRepository.findBySnapshot(
                    runId, source.sourceSnapshotHash(), PROMPT_VERSION);
            if (raced.isPresent()) {
                return raced.get();
            }
            try {
                return insightRepository.insertPending(candidate);
            } catch (DuplicateKeyException duplicate) {
                return insightRepository.findBySnapshot(
                                runId, source.sourceSnapshotHash(), PROMPT_VERSION)
                        .orElseThrow(() -> new IllegalStateException(
                                "insight insert conflicted without an existing row", duplicate));
            }
        });
    }
}
