package com.pulseink.service.knowledge;

import java.util.List;
import java.util.Objects;

/**
 * Immutable extracted text with a detected MIME type and ordered heading-aware sections.
 */
public record ExtractedDocument(
        String title,
        String detectedMimeType,
        List<ExtractedSection> sections) {

    public ExtractedDocument {
        detectedMimeType = Objects.requireNonNull(detectedMimeType, "detectedMimeType must not be null");
        sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
        if (sections.isEmpty()) {
            throw new IllegalArgumentException("extracted document has no sections");
        }
    }
}
