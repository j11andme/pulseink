package com.pulseink.sandbox.repository;

import com.pulseink.sandbox.domain.ChannelPost;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the sandbox channel store. Callers compose post + initial metric +
 * outbox insert into one transaction; each method stays a plain JDBC statement.
 */
public interface ChannelPostRepository {

    /** Inserts the post; throws {@code DuplicateIdempotencyKeyException} on a key collision. */
    long insert(ChannelPost post);

    Optional<ChannelPost> findByIdempotencyKey(UUID idempotencyKey);

    void insertMetric(long channelPostId, LocalDate metricDate,
                      long views, long clicks, long likes);
}
