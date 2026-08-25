package com.pulseink.sandbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.sandbox.domain.Channel;
import com.pulseink.sandbox.domain.ChannelApiException;
import com.pulseink.sandbox.domain.ChannelApiException.Code;
import com.pulseink.sandbox.domain.ChannelPost;
import com.pulseink.sandbox.domain.DuplicateIdempotencyKeyException;
import com.pulseink.sandbox.domain.FeedbackEvent;
import com.pulseink.sandbox.outbox.EventOutboxRepository;
import com.pulseink.sandbox.repository.ChannelPostRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent channel publishing rules. A first request atomically creates the channel post, the
 * initial metric row and the outbox event; a repeated key replays the stored post when the
 * normalized payload matches and otherwise fails with a stable conflict.
 */
public class ChannelPublishingService {

    /** Deterministic demo metrics: every new post starts with the same fixed counters. */
    private static final long INITIAL_VIEWS = 100;
    private static final long INITIAL_CLICKS = 12;
    private static final long INITIAL_LIKES = 4;

    private final ChannelPostRepository posts;
    private final EventOutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ZoneId businessZone;

    public ChannelPublishingService(ChannelPostRepository posts,
                                    EventOutboxRepository outbox,
                                    ObjectMapper objectMapper,
                                    TransactionTemplate transactions,
                                    Clock clock,
                                    ZoneId businessZone) {
        this.posts = Objects.requireNonNull(posts);
        this.outbox = Objects.requireNonNull(outbox);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.businessZone = Objects.requireNonNull(businessZone);
    }

    public PublishResult publish(PublishCommand command, UUID idempotencyKey) {
        validate(command, idempotencyKey);
        String payloadHash = payloadHash(command);
        try {
            return transactions.execute(status -> {
                Instant publishedAt = clock.instant();
                LocalDate metricDate = publishedAt.atZone(businessZone).toLocalDate();
                UUID externalPostId = UUID.randomUUID();
                long postId = posts.insert(new ChannelPost(0L, externalPostId, idempotencyKey,
                        command.sourcePublicationId(), command.contentVersionId(),
                        command.channel(), command.content(), command.sourceRefs(),
                        payloadHash, publishedAt));
                posts.insertMetric(postId, metricDate, INITIAL_VIEWS, INITIAL_CLICKS, INITIAL_LIKES);
                outbox.insert(FeedbackEvent.recorded(UUID.randomUUID(), externalPostId,
                        command.sourcePublicationId(), command.contentVersionId(),
                        command.channel(), publishedAt, metricDate,
                        INITIAL_VIEWS, INITIAL_CLICKS, INITIAL_LIKES));
                return new PublishResult(externalPostId, idempotencyKey, command.channel(),
                        publishedAt, false);
            });
        } catch (DuplicateIdempotencyKeyException duplicate) {
            return replay(command, duplicate.idempotencyKey());
        }
    }

    public PublishResult findByKey(UUID idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotency key must not be null");
        return posts.findByIdempotencyKey(idempotencyKey)
                .map(ChannelPublishingService::toReplayedResult)
                .orElseThrow(() -> new ChannelApiException(Code.CHANNEL_POST_NOT_FOUND,
                        "channel post for idempotency key " + idempotencyKey + " was not found"));
    }

    private PublishResult replay(PublishCommand command, UUID idempotencyKey) {
        var existing = posts.findByIdempotencyKey(idempotencyKey).orElseThrow(() ->
                new ChannelApiException(Code.CHANNEL_POST_NOT_FOUND,
                        "channel post for idempotency key " + idempotencyKey + " was not found"));
        if (!existing.payloadHash().equals(payloadHash(command))) {
            throw new ChannelApiException(Code.IDEMPOTENCY_CONFLICT,
                    "idempotency key " + idempotencyKey + " was already used with a different payload");
        }
        return toReplayedResult(existing);
    }

    private static PublishResult toReplayedResult(ChannelPost post) {
        return new PublishResult(post.externalPostId(), post.idempotencyKey(),
                post.channel(), post.publishedAt(), true);
    }

    private void validate(PublishCommand command, UUID idempotencyKey) {
        Objects.requireNonNull(idempotencyKey, "idempotency key must not be null");
        if (command.sourcePublicationId() <= 0 || command.contentVersionId() <= 0) {
            throw new ChannelApiException(Code.VALIDATION_ERROR,
                    "source publication id and content version id must be positive");
        }
        if (Channel.fromName(command.channel()) == null) {
            throw new ChannelApiException(Code.VALIDATION_ERROR,
                    "unsupported channel: " + command.channel());
        }
        if (command.content() == null || command.content().isEmpty()) {
            throw new ChannelApiException(Code.VALIDATION_ERROR,
                    "content must contain a non-blank title and body");
        }
        for (String field : List.of("title", "body")) {
            Object value = command.content().get(field);
            if (!(value instanceof String text) || text.isBlank()) {
                throw new ChannelApiException(Code.VALIDATION_ERROR,
                        "content." + field + " must be a non-blank string");
            }
        }
        if (command.sourceRefs() == null) {
            throw new ChannelApiException(Code.VALIDATION_ERROR,
                    "sourceRefs must not be null");
        }
    }

    /**
     * Normalized payload hash: stable JSON field order covering channel, sorted content keys and
     * the ordered sourceRefs list; SHA-256 hex only, no second copy of the payload is stored.
     */
    private String payloadHash(PublishCommand command) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("channel", command.channel());
        canonical.put("content", new TreeMap<>(command.content()));
        canonical.put("sourceRefs", List.copyOf(command.sourceRefs()));
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(canonical);
        } catch (JsonProcessingException exception) {
            throw new ChannelApiException(Code.VALIDATION_ERROR,
                    "payload cannot be normalized");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record PublishCommand(
            long sourcePublicationId,
            long contentVersionId,
            String channel,
            Map<String, Object> content,
            List<String> sourceRefs) {
    }

    public record PublishResult(
            UUID externalPostId,
            UUID idempotencyKey,
            String channel,
            Instant publishedAt,
            boolean replayed) {
    }
}
