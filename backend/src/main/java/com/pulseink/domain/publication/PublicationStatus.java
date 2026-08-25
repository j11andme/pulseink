package com.pulseink.domain.publication;

/**
 * Publication lifecycle. PENDING rows are picked up by the worker, which moves them through
 * SENDING while the Channel HTTP call is in flight; transient failures park in RETRY_WAIT until
 * {@code nextAttemptAt}. Terminal states are PUBLISHED and FAILED.
 */
public enum PublicationStatus {
    PENDING,
    SENDING,
    RETRY_WAIT,
    PUBLISHED,
    FAILED
}
