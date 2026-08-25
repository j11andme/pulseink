package com.pulseink.service.memory;

import com.pulseink.config.properties.MemoryProperties;
import com.pulseink.domain.memory.CampaignInsight;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derived-index worker. Claims APPROVED rows with a CAS transition, embeds and indexes strictly
 * outside any transaction, then marks INDEXED; failures park in RETRY_WAIT with a fixed backoff
 * and exhaust to FAILED after the configured attempt limit. The human decision is never rolled
 * back by an index outage.
 */
public final class InsightIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(InsightIndexWorker.class);
    private static final int BATCH_SIZE = 20;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);

    private final CampaignInsightRepository repository;
    private final InsightSearchStore store;
    private final MemoryProperties properties;
    private final Clock clock;

    public InsightIndexWorker(CampaignInsightRepository repository,
                              InsightSearchStore store,
                              MemoryProperties properties,
                              Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.store = Objects.requireNonNull(store);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public void processBatch() {
        processBatch(clock.instant());
    }

    public void processBatch(Instant now) {
        List<CampaignInsight> due = repository.claimIndexDue(now, BATCH_SIZE);
        for (CampaignInsight insight : due) {
            try {
                store.indexApproved(insight);
                repository.markIndexed(insight.id(), insight.version(), clock.instant());
            } catch (RuntimeException failure) {
                handleFailure(insight, failure, now);
            }
        }
    }

    private void handleFailure(CampaignInsight insight, RuntimeException failure, Instant now) {
        String error = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        log.warn("insight index attempt failed insightId={} attempts={}",
                insight.id(), insight.indexAttempts());
        if (insight.indexAttempts() >= properties.indexMaxAttempts()) {
            repository.markIndexFailed(insight.id(), insight.version(), error);
        } else {
            repository.markIndexRetry(insight.id(), insight.version(),
                    now.plus(RETRY_BACKOFF), error);
        }
    }
}
