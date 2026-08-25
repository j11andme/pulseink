package com.pulseink.controller.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.service.feedback.FeedbackIngestionService;
import com.pulseink.service.feedback.FeedbackRepository;
import com.pulseink.support.BackendTestInfra;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "pulseink.auth.jwt-secret=01234567890123456789012345678901",
        "pulseink.auth.demo-password=pulseink-demo",
        "pulseink.model.provider=fake",
        "pulseink.publication.worker-enabled=false",
        "pulseink.feedback.consumer-enabled=true",
        "pulseink.feedback.consumer-max-attempts=3"
})
class FeedbackKafkaIT {

    private static final String RAW_TOPIC = "pulseink.feedback.raw.v1-it-" + UUID.randomUUID();
    private static final String DLT_TOPIC = RAW_TOPIC + "-dlt";
    private static final String GROUP_ID = "pulseink-feedback-it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", BackendTestInfra::datasourceUrl);
        registry.add("spring.datasource.username", BackendTestInfra::datasourceUsername);
        registry.add("spring.datasource.password", BackendTestInfra::datasourcePassword);
        registry.add("spring.kafka.bootstrap-servers", BackendTestInfra::kafkaBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP_ID);
        registry.add("pulseink.feedback.topic", () -> RAW_TOPIC);
        registry.add("pulseink.feedback.dlt-topic", () -> DLT_TOPIC);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired CountingFeedbackIngestion countingIngestion;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void seed() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : new String[] {"content_metric_daily", "feedback_inbox", "publication",
                "approval_record", "content_version", "content_item", "campaign_run", "campaign",
                "app_user"}) {
            jdbc.execute("DELETE FROM " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role,enabled) VALUES (1,'editor','x','EDITOR',TRUE)");
        jdbc.update("INSERT INTO campaign(name,objective,audience,channels_json,constraints_json,status,created_by,version) VALUES ('c','o','a','[\"BLOG\"]','[]','DRAFT',1,0)");
        long campaign = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO campaign_run(campaign_id,requested_policy,state,version) VALUES (?,'DIRECT','PUBLISHING',0)", campaign);
        long run = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO content_item(run_id,task_id,current_version_no,version) VALUES (?,'task',1,0)", run);
        long item = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO content_version(content_item_id,version_no,content_json,source_refs_json,origin) VALUES (?,1,'{}','[]','HUMAN')", item);
        long version = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO approval_record(content_version_id,actor_id,comment_text) VALUES (?,1,'ok')", version);
        long approval = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        // Publication id 31 matches the shared Kafka Feedback Event V1 contract fixture.
        jdbc.update("""
                INSERT INTO publication(id,run_id,content_version_id,approval_record_id,
                                        requested_by,channel,idempotency_key,status,
                                        next_attempt_at,version)
                VALUES (31,?,?,?,1,'BLOG',?,'PUBLISHED',UTC_TIMESTAMP(6),0)
                """, run, version, approval, UUID.randomUUID().toString());
    }

    @Test
    void validEventIsIngestedAndOffsetIsCommitted() throws Exception {
        String payload = contractPayload(UUID.randomUUID().toString());
        kafkaTemplate.send(RAW_TOPIC, "43c5f8d4-88a0-44fd-a46a-b79c66e26b18", payload)
                .get(10, TimeUnit.SECONDS);

        awaitUntil(() -> inboxCount("SELECT COUNT(*) FROM feedback_inbox") >= 1);
        assertThat(metrics()).isEqualTo("100,12,4");
        awaitUntil(() -> committedOffset() >= 1);
    }

    @Test
    void duplicateDeliveryCountsOnlyOnce() throws Exception {
        String payload = contractPayload(UUID.randomUUID().toString());
        kafkaTemplate.send(RAW_TOPIC, "43c5f8d4-88a0-44fd-a46a-b79c66e26b18", payload)
                .get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(RAW_TOPIC, "43c5f8d4-88a0-44fd-a46a-b79c66e26b18", payload)
                .get(10, TimeUnit.SECONDS);

        awaitUntil(() -> inboxCount("SELECT COUNT(*) FROM feedback_inbox") >= 1);
        sleepQuietly(2_000);
        assertThat(metrics()).isEqualTo("100,12,4");
    }

    @Test
    void poisonEventReachesDltWithBoundedAttemptsAndAuditHeaders() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String poison = """
                {"schemaVersion":99,"eventId":"%s","eventType":"CHANNEL_METRICS_RECORDED",
                 "occurredAt":"2026-08-13T12:01:00Z",
                 "externalPostId":"43c5f8d4-88a0-44fd-a46a-b79c66e26b18",
                 "publicationId":31,"contentVersionId":12,"channel":"BLOG",
                 "metricDate":"2026-08-13","deltas":{"views":100,"clicks":12,"likes":4}}
                """.formatted(eventId);
        int attemptsBefore = countingIngestion.attempts();
        kafkaTemplate.send(RAW_TOPIC, "poison-key", poison).get(10, TimeUnit.SECONDS);

        var dltRecords = drainDlt();
        assertThat(dltRecords).isNotEmpty();
        var headers = dltRecords.getFirst().headers();
        assertThat(new String(headers.lastHeader("kafka_dlt-original-topic").value(),
                StandardCharsets.UTF_8)).isEqualTo(RAW_TOPIC);
        assertThat(headers.lastHeader("kafka_dlt-original-partition")).isNotNull();
        assertThat(headers.lastHeader("kafka_dlt-original-offset")).isNotNull();
        assertThat(headers.lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        assertThat(countingIngestion.attempts() - attemptsBefore).isBetween(1, 3);
        assertThat(inboxCount("SELECT COUNT(*) FROM feedback_inbox")).isZero();
    }

    private String contractPayload(String eventId) throws Exception {
        JsonNode fixture = objectMapper.readTree(getClass()
                .getResourceAsStream("/fixtures/feedback-event-v1.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) fixture).put("eventId", eventId);
        return objectMapper.writeValueAsString(fixture);
    }

    private int inboxCount(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private String metrics() {
        return jdbc.query("""
                        SELECT CONCAT(views, ',', clicks, ',', likes)
                        FROM content_metric_daily WHERE publication_id = 31
                        """, (resultSet, rowNum) -> resultSet.getString(1))
                .stream().findFirst().orElse(null);
    }

    private long committedOffset() {
        var properties = new Properties();
        properties.put(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                BackendTestInfra.kafkaBootstrapServers());
        try (AdminClient admin = AdminClient.create(properties)) {
            var offsets = admin.listConsumerGroupOffsets(GROUP_ID)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            return offsets.values().stream()
                    .mapToLong(metadata -> metadata.offset())
                    .max().orElse(-1L);
        } catch (Exception failure) {
            return -1L;
        }
    }

    private List<ConsumerRecord<String, String>> drainDlt() {
        Map<String, Object> properties = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BackendTestInfra.kafkaBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        long deadline = System.currentTimeMillis() + 20_000;
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(DLT_TOPIC));
            while (System.currentTimeMillis() < deadline && records.isEmpty()) {
                for (ConsumerRecord<String, String> record
                        : consumer.poll(Duration.ofMillis(300))) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    private static void awaitUntil(Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            sleepQuietly(100);
        }
        throw new AssertionError("condition was not met within the deadline");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @TestConfiguration
    static class CountingIngestionConfiguration {

        @Bean
        @Primary
        CountingFeedbackIngestion countingIngestion(
                FeedbackRepository feedbackRepository,
                PlatformTransactionManager transactionManager,
                @Value("${pulseink.business-zone:Asia/Shanghai}") String businessZone) {
            return new CountingFeedbackIngestion(
                    feedbackRepository,
                    new TransactionTemplate(transactionManager),
                    java.time.ZoneId.of(businessZone));
        }
    }

    static final class CountingFeedbackIngestion extends FeedbackIngestionService {

        private final AtomicInteger attempts = new AtomicInteger();

        CountingFeedbackIngestion(FeedbackRepository repository,
                                  TransactionTemplate transactions,
                                  java.time.ZoneId businessZone) {
            super(repository, transactions, businessZone);
        }

        @Override
        public boolean consume(com.pulseink.domain.feedback.FeedbackEvent event,
                               String sourceTopic, int sourcePartition, long sourceOffset) {
            attempts.incrementAndGet();
            return super.consume(event, sourceTopic, sourcePartition, sourceOffset);
        }

        int attempts() {
            return attempts.get();
        }
    }
}
