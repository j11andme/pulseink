package com.pulseink.client.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.service.feedback.FeedbackIngestionService;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for the raw feedback topic. It only deserializes, delegates to the ingestion
 * service and acknowledges after the database transaction committed; every failure propagates
 * to the configured error handler for bounded retries and DLT forwarding.
 */
@Component
@ConditionalOnProperty(name = "pulseink.feedback.consumer-enabled",
        havingValue = "true", matchIfMissing = false)
public class FeedbackConsumer {

    private static final Logger log = LoggerFactory.getLogger(FeedbackConsumer.class);

    private final FeedbackIngestionService ingestion;
    private final ObjectMapper objectMapper;

    public FeedbackConsumer(FeedbackIngestionService ingestion, ObjectMapper objectMapper) {
        this.ingestion = Objects.requireNonNull(ingestion);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @KafkaListener(topics = "${pulseink.feedback.topic}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        try {
            var event = objectMapper.readValue(record.value(), FeedbackEventMessage.class)
                    .toDomain();
            ingestion.consume(event, record.topic(), record.partition(), record.offset());
            acknowledgment.acknowledge();
        } catch (JsonProcessingException malformed) {
            throw new IllegalArgumentException(
                    "feedback event payload is not valid contract JSON", malformed);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            log.warn("feedback event delivery failed topic={} partition={} offset={}",
                    record.topic(), record.partition(), record.offset());
            throw failure;
        }
    }
}
