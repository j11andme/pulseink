package com.pulseink.service.embedding;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable embedding configuration fingerprint. The profileId is derived from
 * {@code providerId:modelId:dimensions} and never contains keys or base URLs; it drives
 * Elasticsearch index compatibility checks.
 */
public record EmbeddingProfile(
        String providerId,
        String modelId,
        int dimensions,
        String profileId) {

    public EmbeddingProfile {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        Objects.requireNonNull(modelId, "modelId must not be null");
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        profileId = Objects.requireNonNull(profileId, "profileId must not be null");
    }

    public static EmbeddingProfile of(String providerId, String modelId, int dimensions) {
        var raw = providerId + ":" + modelId + ":" + dimensions;
        byte[] digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
        String hash = java.util.HexFormat.of().formatHex(digest).substring(0, 12);
        String safeProvider = providerId.replaceAll("[^A-Za-z0-9_-]", "-");
        return new EmbeddingProfile(providerId, modelId, dimensions,
                safeProvider + "-" + modelId + "-" + dimensions + "-" + hash);
    }
}
