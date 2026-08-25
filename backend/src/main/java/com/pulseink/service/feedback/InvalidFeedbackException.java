package com.pulseink.service.feedback;

/**
 * A Kafka feedback event failed schema, zone or existence validation and must be treated as
 * poison: the consumer retries it a bounded number of times and then forwards it to the DLT.
 */
public class InvalidFeedbackException extends RuntimeException {

    public InvalidFeedbackException(String message) {
        super(message);
    }
}
