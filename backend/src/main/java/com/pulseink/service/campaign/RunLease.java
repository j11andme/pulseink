package com.pulseink.service.campaign;

import java.time.Instant;

/**
 * One acquired cross-instance run lease. {@code ownerId} plus a random token is the stored
 * value; renew and release must only succeed with the exact same token.
 */
public record RunLease(
        long runId,
        String ownerId,
        String token,
        Instant acquiredAt) {

    public String value() {
        return ownerId + "|" + token;
    }
}
