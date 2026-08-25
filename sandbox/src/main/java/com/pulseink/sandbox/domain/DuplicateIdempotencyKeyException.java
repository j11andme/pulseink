package com.pulseink.sandbox.domain;

import java.util.UUID;

/**
 * Raised when an insert collides with the unique idempotency key of an existing channel post.
 * The publishing service then reads the existing row and decides between replay and conflict.
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    private final UUID idempotencyKey;

    public DuplicateIdempotencyKeyException(UUID idempotencyKey, Throwable cause) {
        super("idempotency key already used: " + idempotencyKey, cause);
        this.idempotencyKey = idempotencyKey;
    }

    public UUID idempotencyKey() {
        return idempotencyKey;
    }
}
