package com.pulseink.service.publishing;

/**
 * Transient channel failure (timeout, connection error or 5xx). The worker parks the
 * publication in RETRY_WAIT and retries with the same idempotency key.
 */
public final class ChannelUnavailableException extends RuntimeException {

    public ChannelUnavailableException(String message) {
        super(message);
    }

    public ChannelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
