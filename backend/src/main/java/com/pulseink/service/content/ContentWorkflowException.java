package com.pulseink.service.content;

public final class ContentWorkflowException extends RuntimeException {
    private final ContentErrorCode code;

    public ContentWorkflowException(ContentErrorCode code, String message) {
        super(message);
        this.code = java.util.Objects.requireNonNull(code);
    }

    public ContentErrorCode code() { return code; }
}
