package com.pulseink.sandbox.outbox;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Kafka-backed sender. The synchronous get with a bounded timeout turns broker failures into
 * deterministic exceptions for the publisher's retry and DLT decisions.
 */
public class KafkaOutboxMessageSender implements OutboxMessageSender {

    private final KafkaTemplate<String, String> template;

    public KafkaOutboxMessageSender(KafkaTemplate<String, String> template) {
        this.template = Objects.requireNonNull(template);
    }

    @Override
    public void send(String topic, String key, String payload) {
        try {
            template.send(topic, key, payload).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while sending outbox event", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("kafka send failed", failure);
        }
    }
}
