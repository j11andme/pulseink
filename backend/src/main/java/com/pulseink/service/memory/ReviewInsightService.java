package com.pulseink.service.memory;

import com.pulseink.domain.memory.CampaignInsight;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Human decision rules. A candidate can only be decided once from PENDING; replaying the same
 * direction returns the current row, and a conflicting direction is a stable 409. APPROVED
 * rows become INDEX_PENDING in the same short transaction, REJECTED rows stay NOT_INDEXED.
 */
public final class ReviewInsightService implements ReviewInsightUseCase {

    private final CampaignInsightRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReviewInsightService(CampaignInsightRepository repository,
                                TransactionTemplate transactions,
                                Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public CampaignInsight decide(long insightId, InsightDecision decision,
                                  String comment, long actorId) {
        if (insightId <= 0 || actorId <= 0 || decision == null) {
            throw new InsightException(InsightErrorCode.VALIDATION_ERROR,
                    "insight id, actor id and decision are required");
        }
        return transactions.execute(status -> {
            var insight = repository.findById(insightId).orElseThrow(() ->
                    new InsightException(InsightErrorCode.INSIGHT_NOT_FOUND,
                            "insight " + insightId + " was not found"));
            var target = decision.targetStatus();
            if (insight.status() == target) {
                return insight;
            }
            if (insight.status() != com.pulseink.domain.memory.InsightStatus.PENDING) {
                throw new InsightException(InsightErrorCode.INSIGHT_DECISION_CONFLICT,
                        "insight " + insightId + " is no longer PENDING");
            }
            try {
                return repository.decidePending(insightId, insight.version(), target,
                        comment, actorId, clock.instant());
            } catch (IllegalStateException stale) {
                throw new InsightException(InsightErrorCode.INSIGHT_DECISION_CONFLICT,
                        "insight " + insightId + " changed concurrently");
            }
        });
    }
}
