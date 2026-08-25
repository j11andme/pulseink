package com.pulseink.sandbox.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.sandbox.domain.FeedbackEvent;
import com.pulseink.sandbox.support.SandboxTestInfra;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class OutboxPublisherIT {

    private static final String RAW_TOPIC = "pulseink.feedback.raw.v1-it-" + UUID.randomUUID();
    private static final String DLT_TOPIC = RAW_TOPIC + "-dlt";

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SandboxTestInfra::datasourceUrl);
        registry.add("spring.datasource.username", SandboxTestInfra::datasourceUsername);
        registry.add("spring.datasource.password", SandboxTestInfra::datasourcePassword);
        registry.add("spring.kafka.bootstrap-servers", SandboxTestInfra::kafkaBootstrapServers);
        registry.add("pulseink.feedback.topic", () -> RAW_TOPIC);
        registry.add("pulseink.feedback.dlt-topic", () -> DLT_TOPIC);
    }

    @Autowired EventOutboxRepository outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired FlakySender flakySender;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void reset() {
        jdbc.execute("DELETE FROM event_outbox");
        flakySender.reset();
        clock().advanceTo(Instant.parse("2026-08-13T12:00:00Z"));
    }

    @Test
    void normalEventIsPublishedOnceAndMarkedPublished() throws Exception {
        FeedbackEvent event = event();

        outbox.insert(event);
        publisher.publishBatch();

        assertThat(status(event.eventId())).isEqualTo("PUBLISHED");
        var delivered = occurrences(RAW_TOPIC, event.eventId(), 1);
        assertThat(delivered).hasSize(1);
        ConsumerRecord<String, String> record = delivered.getFirst();
        assertThat(record.key()).isEqualTo(event.externalPostId().toString());
        var payload = objectMapper.readTree(record.value());
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(payload.get("eventType").asText()).isEqualTo("CHANNEL_METRICS_RECORDED");
        assertThat(payload.get("deltas").get("views").asLong()).isEqualTo(100);
    }

    @Test
    void crashBetweenKafkaSendAndDatabaseMarkAllowsRedelivery() throws Exception {
        FeedbackEvent event = event();
        outbox.insert(event);

        // Claim (SENDING) and send to Kafka, then "crash" before the PUBLISHED mark.
        List<OutboxEnvelope> claimed = outbox.claimDue(clock.instant(), 10);
        assertThat(claimed).singleElement().satisfies(envelope ->
                assertThat(envelope.status()).isEqualTo("SENDING"));
        kafkaTemplate.send(RAW_TOPIC, event.externalPostId().toString(),
                claimed.getFirst().payloadJson()).get(10, TimeUnit.SECONDS);

        // After the visibility deadline the stuck row is reclaimed and redelivered.
        clock().advance(Duration.ofSeconds(6));
        publisher.publishBatch();

        assertThat(status(event.eventId())).isEqualTo("PUBLISHED");
        assertThat(occurrences(RAW_TOPIC, event.eventId(), 2)).hasSize(2);
        assertThat(attemptCount(event.eventId())).isEqualTo(2);
    }

    @Test
    void transientFailureRetriesAfterBackoff() throws Exception {
        FeedbackEvent event = event();
        outbox.insert(event);
        flakySender.failMainTimes = 1;

        publisher.publishBatch();
        assertThat(status(event.eventId())).isEqualTo("RETRY_WAIT");
        assertThat(attemptCount(event.eventId())).isEqualTo(1);

        clock().advance(Duration.ofSeconds(6));
        publisher.publishBatch();

        assertThat(status(event.eventId())).isEqualTo("PUBLISHED");
        assertThat(attemptCount(event.eventId())).isEqualTo(2);
        assertThat(occurrences(RAW_TOPIC, event.eventId(), 1)).hasSize(1);
    }

    @Test
    void permanentPoisonPayloadReachesDltAndDeadAfterBoundedAttempts() throws Exception {
        FeedbackEvent event = event();
        outbox.insert(event);
        flakySender.failMainAlways = true;

        publisher.publishBatch();
        clock().advance(Duration.ofSeconds(6));
        publisher.publishBatch();
        clock().advance(Duration.ofSeconds(6));
        publisher.publishBatch();

        assertThat(status(event.eventId())).isEqualTo("DEAD");
        assertThat(attemptCount(event.eventId())).isEqualTo(3);
        assertThat(occurrences(RAW_TOPIC, event.eventId(), 0)).isEmpty();
        assertThat(occurrences(DLT_TOPIC, event.eventId(), 1)).hasSize(1);
    }

    private FeedbackEvent event() {
        return FeedbackEvent.recorded(UUID.randomUUID(), UUID.randomUUID(), 31L, 12L, "BLOG",
                clock().instant(), LocalDate.of(2026, 8, 13),
                100, 12, 4);
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM event_outbox WHERE event_id = ?", String.class,
                eventId.toString());
    }

    private int attemptCount(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT attempt_count FROM event_outbox WHERE event_id = ?", Integer.class,
                eventId.toString());
    }

    private List<ConsumerRecord<String, String>> occurrences(String topic, UUID eventId,
                                                             int expected) {
        Map<String, Object> properties = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SandboxTestInfra.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        long deadline = System.currentTimeMillis() + 20_000;
        var matches = new java.util.ArrayList<ConsumerRecord<String, String>>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(topic));
            long quietSince = System.currentTimeMillis();
            while (System.currentTimeMillis() < deadline) {
                var records = consumer.poll(Duration.ofMillis(300));
                if (!records.isEmpty()) {
                    quietSince = System.currentTimeMillis();
                }
                for (var record : records) {
                    if (eventId.toString().equals(parseEventId(record.value()))) {
                        matches.add(record);
                    }
                }
                if (expected > 0 && matches.size() >= expected) {
                    return matches;
                }
                if (expected == 0 && System.currentTimeMillis() - quietSince > 2_000) {
                    return matches;
                }
            }
        }
        return matches;
    }

    private String parseEventId(String payload) {
        try {
            return objectMapper.readTree(payload).get("eventId").asText();
        } catch (Exception exception) {
            return "";
        }
    }

    private MutableClock clock() {
        return (MutableClock) clock;
    }

    @TestConfiguration
    static class TestClocksAndSenders {

        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(Instant.parse("2026-08-13T12:00:00Z"));
        }

        @Bean
        @Primary
        OutboxMessageSender flakySender(KafkaTemplate<String, String> template) {
            return new FlakySender(new KafkaOutboxMessageSender(template));
        }
    }

    static final class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        void advanceTo(Instant target) {
            instant = target;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final class FlakySender implements OutboxMessageSender {

        private final OutboxMessageSender delegate;
        volatile int failMainTimes;
        volatile boolean failMainAlways;
        volatile boolean failDlt;

        FlakySender(OutboxMessageSender delegate) {
            this.delegate = delegate;
        }

        void reset() {
            failMainTimes = 0;
            failMainAlways = false;
            failDlt = false;
        }

        @Override
        public void send(String topic, String key, String payload) {
            boolean dlt = topic.endsWith("-dlt");
            if (dlt && failDlt) {
                throw new IllegalStateException("simulated DLT failure");
            }
            if (!dlt && (failMainAlways || failMainTimes > 0)) {
                failMainTimes--;
                throw new IllegalStateException("simulated kafka send failure");
            }
            delegate.send(topic, key, payload);
        }
    }
}
