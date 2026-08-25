package com.pulseink.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulseink.publication")
public record PublicationProperties(
        Boolean workerEnabled,
        Duration pollDelay,
        Integer batchSize,
        Integer maxAttempts,
        Duration retryDelay) {

    public PublicationProperties {
        if (workerEnabled == null) workerEnabled = true;
        if (pollDelay == null) pollDelay = Duration.ofSeconds(1);
        if (batchSize == null || batchSize <= 0) batchSize = 20;
        if (maxAttempts == null || maxAttempts <= 0) maxAttempts = 3;
        if (retryDelay == null) retryDelay = Duration.ofSeconds(5);
    }
}
