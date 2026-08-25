package com.pulseink.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding configuration, fully independent from the chat model. Fake is the default and needs
 * no secrets; openai-compatible requires a complete public configuration.
 */
@ConfigurationProperties("pulseink.embedding")
public record EmbeddingProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        Integer dimensions,
        String dimensionField,
        Integer batchSize,
        Duration timeout) {

    public EmbeddingProperties {
        if (provider == null || provider.isBlank()) {
            provider = "fake";
        }
        if (!java.util.Set.of("fake", "openai-compatible").contains(provider)) {
            throw new IllegalStateException(
                    "pulseink.embedding.provider must be fake or openai-compatible");
        }
        if (dimensions != null && dimensions < 0) {
            throw new IllegalStateException(
                    "pulseink.embedding.dimensions must be positive");
        }
        if (dimensions == null || dimensions == 0) {
            dimensions = 64;
        }
        if (dimensionField == null || dimensionField.isBlank()) {
            dimensionField = "dimensions";
        }
        if (!java.util.Set.of("dimension", "dimensions", "none").contains(dimensionField)) {
            throw new IllegalStateException(
                    "pulseink.embedding.dimension-field must be dimension, dimensions or none");
        }
        if (batchSize != null && batchSize < 0) {
            throw new IllegalStateException(
                    "pulseink.embedding.batch-size must be positive");
        }
        if (batchSize == null || batchSize == 0) {
            batchSize = 16;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }
    }
}
