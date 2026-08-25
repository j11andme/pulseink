package com.pulseink.sandbox.outbox;

import com.pulseink.sandbox.config.properties.OutboxProperties;
import com.pulseink.sandbox.config.properties.FeedbackProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Publishes due outbox events. The claim runs in a short transaction; every Kafka send happens
 * strictly outside it. Success marks PUBLISHED, transient failures park in RETRY_WAIT with a
 * fixed delay, and after the attempt limit the envelope is forwarded to the DLT topic: only a
 * successful DLT delivery marks the row DEAD, so a Kafka outage keeps rows recoverable.
 */
public class OutboxPublisher {

    private final EventOutboxRepository outbox;
    private final OutboxMessageSender sender;
    private final OutboxProperties outboxProperties;
    private final FeedbackProperties feedbackProperties;
    private final Clock clock;

    public OutboxPublisher(EventOutboxRepository outbox,
                           OutboxMessageSender sender,
                           OutboxProperties outboxProperties,
                           FeedbackProperties feedbackProperties,
                           Clock clock) {
        this.outbox = Objects.requireNonNull(outbox);
        this.sender = Objects.requireNonNull(sender);
        this.outboxProperties = Objects.requireNonNull(outboxProperties);
        this.feedbackProperties = Objects.requireNonNull(feedbackProperties);
        this.clock = Objects.requireNonNull(clock);
    }

    public void publishBatch() {
        Instant now = clock.instant();
        List<OutboxEnvelope> batch = outbox.claimDue(now, outboxProperties.batchSize());
        for (OutboxEnvelope envelope : batch) {
            try {
                sender.send(feedbackProperties.topic(), envelope.aggregateId(),
                        envelope.payloadJson());
                outbox.markPublished(envelope.id());
            } catch (RuntimeException failure) {
                handleFailure(envelope, failure, now);
            }
        }
    }

    private void handleFailure(OutboxEnvelope envelope, RuntimeException failure, Instant now) {
        String error = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
        if (envelope.attemptCount() >= outboxProperties.maxAttempts()) {
            try {
                sender.send(feedbackProperties.dltTopic(), envelope.aggregateId(),
                        envelope.payloadJson());
                outbox.markDead(envelope.id(), error);
            } catch (RuntimeException dltFailure) {
                // Kafka as a whole is unavailable: keep the envelope recoverable instead of
                // pretending a DLT that could not be written.
                outbox.markRetryWait(envelope.id(),
                        now.plus(outboxProperties.retryDelay()), error);
            }
        } else {
            outbox.markRetryWait(envelope.id(),
                    now.plus(outboxProperties.retryDelay()), error);
        }
    }
}
