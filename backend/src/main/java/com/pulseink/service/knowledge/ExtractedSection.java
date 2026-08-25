package com.pulseink.service.knowledge;

import java.util.Objects;

/**
 * Immutable section of extracted text with its heading path and ordinal.
 */
public record ExtractedSection(
        String headingPath,
        String text,
        int ordinal) {

    public ExtractedSection {
        Objects.requireNonNull(headingPath, "headingPath must not be null");
        Objects.requireNonNull(text, "text must not be null");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
    }
}
