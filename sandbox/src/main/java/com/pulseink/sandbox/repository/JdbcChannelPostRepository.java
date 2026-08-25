package com.pulseink.sandbox.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.sandbox.domain.ChannelPost;
import com.pulseink.sandbox.domain.DuplicateIdempotencyKeyException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcChannelPostRepository implements ChannelPostRepository {

    private static final TypeReference<Map<String, Object>> CONTENT_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> SOURCE_REFS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcChannelPostRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public long insert(ChannelPost post) {
        var keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO channel_post
                            (external_post_id, idempotency_key, source_publication_id,
                             content_version_id, channel, content_json, source_refs_json,
                             payload_hash, published_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, post.externalPostId().toString());
                statement.setString(2, post.idempotencyKey().toString());
                statement.setLong(3, post.sourcePublicationId());
                statement.setLong(4, post.contentVersionId());
                statement.setString(5, post.channel());
                statement.setString(6, writeJson(post.content()));
                statement.setString(7, writeJson(post.sourceRefs()));
                statement.setString(8, post.payloadHash());
                statement.setObject(9, java.sql.Timestamp.from(post.publishedAt()));
                return statement;
            }, keyHolder);
        } catch (DuplicateKeyException duplicate) {
            throw new DuplicateIdempotencyKeyException(post.idempotencyKey(), duplicate);
        }
        Number generated = Objects.requireNonNull(
                keyHolder.getKey(), "channel post insert did not generate a key");
        return generated.longValue();
    }

    @Override
    public Optional<ChannelPost> findByIdempotencyKey(UUID idempotencyKey) {
        List<ChannelPost> rows = jdbc.query("""
                SELECT id, external_post_id, idempotency_key, source_publication_id,
                       content_version_id, channel, content_json, source_refs_json,
                       payload_hash, published_at
                FROM channel_post
                WHERE idempotency_key = ?
                """, (resultSet, rowNum) -> new ChannelPost(
                resultSet.getLong("id"),
                UUID.fromString(resultSet.getString("external_post_id")),
                UUID.fromString(resultSet.getString("idempotency_key")),
                resultSet.getLong("source_publication_id"),
                resultSet.getLong("content_version_id"),
                resultSet.getString("channel"),
                readJson(resultSet.getString("content_json"), CONTENT_TYPE),
                readJson(resultSet.getString("source_refs_json"), SOURCE_REFS_TYPE),
                resultSet.getString("payload_hash"),
                resultSet.getTimestamp("published_at").toInstant()),
                idempotencyKey.toString());
        return rows.stream().findFirst();
    }

    @Override
    public void insertMetric(long channelPostId, LocalDate metricDate,
                             long views, long clicks, long likes) {
        jdbc.update("""
                INSERT INTO channel_metric
                    (channel_post_id, metric_date, views, clicks, likes)
                VALUES (?, ?, ?, ?, ?)
                """, channelPostId, java.sql.Date.valueOf(metricDate), views, clicks, likes);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("channel post content cannot be serialized", exception);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored channel post JSON is invalid", exception);
        }
    }
}
