package com.pulseink.service.knowledge;

import java.util.Objects;

/**
 * Immutable deterministic chunk of knowledge text with heading context and code-point ranges.
 */
public record KnowledgeChunk(
        int ordinal,
        String headingPath,
        String text,
        int startCodePoint,
        int endCodePoint) {

    public KnowledgeChunk {
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        Objects.requireNonNull(headingPath, "headingPath must not be null");
        Objects.requireNonNull(text, "text must not be null");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("chunk text must not be empty");
        }
        if (startCodePoint < 0 || endCodePoint <= startCodePoint) {
            throw new IllegalArgumentException("invalid code-point range");
        }
    }
}
