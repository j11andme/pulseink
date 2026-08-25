package com.pulseink.service.memory;

public final class InsightException extends RuntimeException {

    private final InsightErrorCode code;

    public InsightException(InsightErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public InsightException(InsightErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public InsightErrorCode code() {
        return code;
    }
}
