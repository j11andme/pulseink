package com.pulseink.sandbox.domain;

/**
 * Protocol-facing channel API failure with a stable machine-readable code.
 */
public class ChannelApiException extends RuntimeException {

    public enum Code {
        VALIDATION_ERROR,
        IDEMPOTENCY_CONFLICT,
        CHANNEL_POST_NOT_FOUND
    }

    private final Code code;

    public ChannelApiException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
