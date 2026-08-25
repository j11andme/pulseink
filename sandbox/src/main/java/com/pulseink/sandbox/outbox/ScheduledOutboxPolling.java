package com.pulseink.sandbox.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler trigger for the outbox publisher. The bean only exists when the publisher is
 * enabled, so ordinary repository tests never start background polling; the core
 * {@link OutboxPublisher#publishBatch()} stays directly callable in tests.
 */
@Component
@ConditionalOnProperty(name = "pulseink.outbox.publisher-enabled",
        havingValue = "true", matchIfMissing = false)
public class ScheduledOutboxPolling {

    private final OutboxPublisher publisher;

    public ScheduledOutboxPolling(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${pulseink.outbox.poll-delay:1s}",
            initialDelayString = "${pulseink.outbox.poll-delay:1s}")
    public void poll() {
        publisher.publishBatch();
    }
}
