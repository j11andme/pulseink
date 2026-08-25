package com.pulseink.agent.model;

/**
 * Typed failure of a model completion call. Carries a stable {@link ModelFailureKind}; the
 * message never contains provider stack traces, API keys or Authorization headers.
 */
public final class ModelCallException extends RuntimeException {

    private final ModelFailureKind failureKind;

    public ModelCallException(ModelFailureKind failureKind, String message) {
        super(message);
        this.failureKind = failureKind;
    }

    public ModelFailureKind failureKind() {
        return failureKind;
    }

    public boolean isRetryable() {
        return failureKind == ModelFailureKind.RATE_LIMIT
                || failureKind == ModelFailureKind.TIMEOUT
                || failureKind == ModelFailureKind.SERVER;
    }

    public boolean isSameProviderRetryable() {
        return failureKind == ModelFailureKind.TIMEOUT
                || failureKind == ModelFailureKind.SERVER;
    }
}
