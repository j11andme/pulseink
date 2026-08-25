package com.pulseink.service.feedback;

import com.pulseink.domain.feedback.ContentMetricDaily;
import com.pulseink.domain.feedback.FeedbackEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Feedback ingestion rules. A valid event is validated, then the inbox insert and the daily
 * metric upsert commit in one transaction; a duplicated eventId returns false and leaves the
 * metrics untouched. Unknown publications, unsupported schema versions, negative counters and
 * zone-inconsistent metricDate values are rejected as poison.
 */
public class FeedbackIngestionService implements QueryMetricsUseCase {

    public static final String EVENT_TYPE = "CHANNEL_METRICS_RECORDED";
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final FeedbackRepository repository;
    private final TransactionTemplate transactions;
    private final ZoneId businessZone;

    public FeedbackIngestionService(FeedbackRepository repository,
                                    TransactionTemplate transactions,
                                    ZoneId businessZone) {
        this.repository = Objects.requireNonNull(repository);
        this.transactions = Objects.requireNonNull(transactions);
        this.businessZone = Objects.requireNonNull(businessZone);
    }

    public boolean consume(FeedbackEvent event, String sourceTopic,
                           int sourcePartition, long sourceOffset) {
        Objects.requireNonNull(event, "event must not be null");
        validate(event);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            try {
                repository.findByPublicationId(event.publicationId());
            } catch (IllegalArgumentException missing) {
                throw new InvalidFeedbackException(
                        "publication " + event.publicationId() + " was not found");
            }
            return repository.ingest(event, sourceTopic, sourcePartition, sourceOffset);
        }));
    }

    @Override
    public List<ContentMetricDaily> findByRunId(long runId) {
        if (runId <= 0) {
            throw new InvalidFeedbackException("run id must be positive");
        }
        return repository.findByRunId(runId);
    }

    private void validate(FeedbackEvent event) {
        if (event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new InvalidFeedbackException(
                    "unsupported schema version " + event.schemaVersion());
        }
        if (!EVENT_TYPE.equals(event.eventType())) {
            throw new InvalidFeedbackException("unsupported event type " + event.eventType());
        }
        if (event.views() < 0 || event.clicks() < 0 || event.likes() < 0) {
            throw new InvalidFeedbackException("metric counters must be non-negative");
        }
        if (event.publicationId() <= 0 || event.contentVersionId() <= 0) {
            throw new InvalidFeedbackException("publication and content version ids must be positive");
        }
        LocalDate zoneDate = event.occurredAt().atZone(businessZone).toLocalDate();
        if (!zoneDate.equals(event.metricDate())) {
            throw new InvalidFeedbackException(
                    "metricDate " + event.metricDate()
                            + " does not match the business zone date " + zoneDate);
        }
    }
}
