package com.pulseink.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulseink.model")
public record ModelProperties(
        String provider,
        String fallbackProvider,
        Duration requestTimeout,
        Provider ark,
        Provider zhipu) {

    public ModelProperties {
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(180) : requestTimeout;
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("model request timeout must be positive");
        }
    }

    public record Provider(
            String apiKey,
            String baseUrl,
            String model) {}
}
