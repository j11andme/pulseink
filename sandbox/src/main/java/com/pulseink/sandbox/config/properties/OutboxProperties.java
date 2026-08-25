package com.pulseink.sandbox.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulseink.outbox")
public record OutboxProperties(
        Boolean publisherEnabled,
        Duration pollDelay,
        Integer batchSize,
        Integer maxAttempts,
        Duration retryDelay) {

    public OutboxProperties {
        if (publisherEnabled == null) publisherEnabled = true;
        if (pollDelay == null) pollDelay = Duration.ofSeconds(1);
        if (batchSize == null || batchSize <= 0) batchSize = 50;
        if (maxAttempts == null || maxAttempts <= 0) maxAttempts = 3;
        if (retryDelay == null) retryDelay = Duration.ofSeconds(5);
    }
}
