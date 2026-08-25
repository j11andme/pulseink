package com.pulseink.sandbox.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.sandbox.domain.FeedbackEvent;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcEventOutboxRepository implements EventOutboxRepository {

    /** SENDING visibility deadline: stuck rows become claimable again after this window. */
    private static final Duration SENDING_VISIBILITY = Duration.ofSeconds(5);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcEventOutboxRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public void insert(FeedbackEvent event) {
        jdbc.update("""
                INSERT INTO event_outbox
                    (event_id, aggregate_type, aggregate_id, event_type, schema_version,
                     payload_json, status, next_attempt_at)
                VALUES (?, 'CHANNEL_POST', ?, 'CHANNEL_METRICS_RECORDED', 1, ?, 'PENDING', ?)
                """, event.eventId().toString(), event.externalPostId().toString(),
                writePayload(event), Timestamp.from(event.occurredAt()));
    }

    @Override
    @Transactional
    public List<OutboxEnvelope> claimDue(Instant now, int batchSize) {
        List<Long> claimedIds = jdbc.queryForList("""
                SELECT id FROM event_outbox
                WHERE status IN ('PENDING', 'RETRY_WAIT', 'SENDING')
                  AND next_attempt_at <= ?
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, Long.class, Timestamp.from(now), batchSize);
        if (claimedIds.isEmpty()) {
            return List.of();
        }
        Timestamp nextAttemptAt = Timestamp.from(now.plus(SENDING_VISIBILITY));
        for (Long id : claimedIds) {
            jdbc.update("""
                    UPDATE event_outbox
                    SET status = 'SENDING',
                        attempt_count = attempt_count + 1,
                        next_attempt_at = ?
                    WHERE id = ?
                    """, nextAttemptAt, id);
        }
        var placeholders = String.join(",", java.util.Collections.nCopies(claimedIds.size(), "?"));
        return jdbc.query("""
                SELECT id, event_id, aggregate_type, aggregate_id, event_type, schema_version,
                       payload_json, status, attempt_count, next_attempt_at, last_error,
                       created_at, published_at
                FROM event_outbox
                WHERE id IN (%s)
                ORDER BY id
                """.formatted(placeholders), (resultSet, rowNum) -> new OutboxEnvelope(
                resultSet.getLong("id"),
                UUID.fromString(resultSet.getString("event_id")),
                resultSet.getString("aggregate_type"),
                resultSet.getString("aggregate_id"),
                resultSet.getString("event_type"),
                resultSet.getInt("schema_version"),
                resultSet.getString("payload_json"),
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("next_attempt_at").toInstant(),
                resultSet.getString("last_error"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("published_at") == null
                        ? null : resultSet.getTimestamp("published_at").toInstant()),
                claimedIds.toArray());
    }

    @Override
    public void markPublished(long id) {
        jdbc.update("""
                UPDATE event_outbox
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND status = 'SENDING'
                """, id);
    }

    @Override
    public void markRetryWait(long id, Instant nextAttemptAt, String error) {
        jdbc.update("""
                UPDATE event_outbox
                SET status = 'RETRY_WAIT', next_attempt_at = ?, last_error = ?
                WHERE id = ? AND status = 'SENDING'
                """, Timestamp.from(nextAttemptAt), truncate(error), id);
    }

    @Override
    public void markDead(long id, String error) {
        jdbc.update("""
                UPDATE event_outbox
                SET status = 'DEAD', last_error = ?
                WHERE id = ? AND status = 'SENDING'
                """, truncate(error), id);
    }

    private String writePayload(FeedbackEvent event) {
        var payload = new OutboxPayloadV1(1, event.eventId().toString(), event.eventType(),
                event.occurredAt(), event.externalPostId().toString(), event.publicationId(),
                event.contentVersionId(), event.channel(), event.metricDate(),
                new OutboxPayloadV1.Deltas(event.views(), event.clicks(), event.likes()));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("feedback event cannot be serialized", exception);
        }
    }

    private String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1_024 ? error : error.substring(0, 1_024);
    }

    /**
     * Kafka Feedback Event V1 wire shape; owned by the sandbox so the domain record stays free
     * of Jackson bindings.
     */
    public record OutboxPayloadV1(
            int schemaVersion,
            String eventId,
            String eventType,
            Instant occurredAt,
            String externalPostId,
            long publicationId,
            long contentVersionId,
            String channel,
            LocalDate metricDate,
            Deltas deltas) {

        public record Deltas(long views, long clicks, long likes) {}
    }
}
