package com.pulseink.service.publishing;

public final class PublicationException extends RuntimeException {

    private final PublicationErrorCode code;

    public PublicationException(PublicationErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public PublicationErrorCode code() {
        return code;
    }
}
