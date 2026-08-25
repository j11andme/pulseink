package com.pulseink.service.publishing;

/**
 * Permanent channel failure: the sandbox definitively refused the payload (for example an
 * idempotency conflict). The publication moves to FAILED and is never retried.
 */
public final class ChannelRejectedException extends RuntimeException {

    private final String code;

    public ChannelRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
