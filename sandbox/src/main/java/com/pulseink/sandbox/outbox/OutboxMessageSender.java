package com.pulseink.sandbox.outbox;

/**
 * Kafka send port used by the outbox publisher so the send step stays swappable in tests.
 */
@FunctionalInterface
public interface OutboxMessageSender {

    void send(String topic, String key, String payload);
}
