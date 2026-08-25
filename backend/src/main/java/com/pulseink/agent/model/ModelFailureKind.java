package com.pulseink.agent.model;

/**
 * Stable failure categories for model calls. Only RATE_LIMIT, TIMEOUT and SERVER are retryable
 * and eligible for a single fallback.
 */
public enum ModelFailureKind {
    RATE_LIMIT,
    TIMEOUT,
    SERVER,
    AUTHENTICATION,
    INVALID_REQUEST,
    EMPTY_RESPONSE,
    UNKNOWN
}
