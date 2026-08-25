package com.pulseink.service.knowledge;

import java.io.InputStream;
import java.util.Objects;

/**
 * Immutable stored file descriptor. Never carries absolute disk paths; only the server-generated
 * storage key is persisted.
 */
public record StoredDocument(
        String storageKey,
        String originalFilename,
        String declaredMimeType,
        long sizeBytes,
        String checksumSha256) {

    public StoredDocument {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        Objects.requireNonNull(originalFilename, "originalFilename must not be null");
        Objects.requireNonNull(declaredMimeType, "declaredMimeType must not be null");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        if (checksumSha256 == null || checksumSha256.length() != 64) {
            throw new IllegalArgumentException("checksumSha256 must be a 64-char hex digest");
        }
    }
}
