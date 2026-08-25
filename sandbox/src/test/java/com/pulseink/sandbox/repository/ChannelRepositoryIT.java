package com.pulseink.sandbox.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.sandbox.domain.ChannelPost;
import com.pulseink.sandbox.domain.FeedbackEvent;
import com.pulseink.sandbox.outbox.EventOutboxRepository;
import com.pulseink.sandbox.support.SandboxTestInfra;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "pulseink.outbox.publisher-enabled=false")
class ChannelRepositoryIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SandboxTestInfra::datasourceUrl);
        registry.add("spring.datasource.username", SandboxTestInfra::datasourceUsername);
        registry.add("spring.datasource.password", SandboxTestInfra::datasourcePassword);
    }

    @Autowired ChannelPostRepository posts;
    @Autowired EventOutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void reset() {
        jdbc.execute("DELETE FROM event_outbox");
        jdbc.execute("DELETE FROM channel_metric");
        jdbc.execute("DELETE FROM channel_post");
    }

    @Test
    void postMetricAndOutboxCommitAtomically() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> insertAggregate());
        assertThat(count("channel_post")).isEqualTo(1);
        assertThat(count("channel_metric")).isEqualTo(1);
        assertThat(count("event_outbox")).isEqualTo(1);
    }

    @Test
    void failureRollsBackAllThreeRows() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            insertAggregate();
            throw new IllegalStateException("injected failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("channel_post") + count("channel_metric") + count("event_outbox")).isZero();
    }

    @Test
    void staleFailureCannotDowngradeAPublishedOutboxEvent() {
        insertAggregate();
        var sending = outbox.claimDue(Instant.parse("2026-08-13T12:01:00Z"), 1)
                .getFirst();

        outbox.markPublished(sending.id());
        outbox.markRetryWait(sending.id(), Instant.parse("2026-08-13T12:02:00Z"),
                "late worker failure");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM event_outbox WHERE id = ?", String.class, sending.id()))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void feedbackV1SerializesJavaTimeValuesAsIsoStrings() {
        insertAggregate();

        assertThat(jdbc.queryForObject("""
                SELECT CONCAT(
                    JSON_TYPE(JSON_EXTRACT(payload_json, '$.occurredAt')), '/',
                    JSON_TYPE(JSON_EXTRACT(payload_json, '$.metricDate')))
                FROM event_outbox
                """, String.class)).isEqualTo("STRING/STRING");
    }

    private void insertAggregate() {
        UUID externalId = UUID.randomUUID();
        long postId = posts.insert(new ChannelPost(0, externalId, UUID.randomUUID(), 31, 12,
                "BLOG", Map.of("body", "hello"), java.util.List.of(), "hash",
                Instant.parse("2026-08-13T12:00:00Z")));
        posts.insertMetric(postId, LocalDate.of(2026, 8, 13), 100, 12, 4);
        outbox.insert(FeedbackEvent.recorded(UUID.randomUUID(), externalId, 31, 12, "BLOG",
                Instant.parse("2026-08-13T12:01:00Z"), LocalDate.of(2026, 8, 13), 100, 12, 4));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
