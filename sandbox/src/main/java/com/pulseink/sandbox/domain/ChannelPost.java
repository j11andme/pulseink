package com.pulseink.sandbox.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One simulated external channel post. The idempotency key is unique: a repeated publish call
 * with the same key and the same normalized payload returns this post instead of creating
 * another one.
 */
public record ChannelPost(
        long id,
        UUID externalPostId,
        UUID idempotencyKey,
        long sourcePublicationId,
        long contentVersionId,
        String channel,
        Map<String, Object> content,
        List<String> sourceRefs,
        String payloadHash,
        Instant publishedAt) {

    public ChannelPost {
        content = Map.copyOf(content);
        sourceRefs = List.copyOf(sourceRefs);
    }
}
