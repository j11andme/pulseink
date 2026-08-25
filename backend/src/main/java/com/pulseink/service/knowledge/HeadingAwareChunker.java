package com.pulseink.service.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic heading-aware chunker. Splits sections by code point with optional overlap,
 * preferring paragraph/sentence/word boundaries and never splitting surrogate pairs. Identical
 * input and configuration always produce identical output.
 */
public final class HeadingAwareChunker {

    private final int maxChunkCodePoints;
    private final int overlap;
    private final int maxChunks;

    public HeadingAwareChunker(int maxChunkCodePoints, int overlap, int maxChunks) {
        if (maxChunkCodePoints <= 0) {
            throw new IllegalArgumentException("maxChunkCodePoints must be positive");
        }
        if (overlap < 0 || overlap >= maxChunkCodePoints) {
            throw new IllegalArgumentException(
                    "overlap must satisfy 0 <= overlap < maxChunkCodePoints");
        }
        if (maxChunks <= 0) {
            throw new IllegalArgumentException("maxChunks must be positive");
        }
        this.maxChunkCodePoints = maxChunkCodePoints;
        this.overlap = overlap;
        this.maxChunks = maxChunks;
    }

    public List<KnowledgeChunk> chunk(ExtractedDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        if (document.sections().isEmpty()) {
            throw new IllegalArgumentException("document has no sections");
        }
        var chunks = new ArrayList<KnowledgeChunk>();
        int ordinal = 0;
        int globalOffset = 0;
        for (var section : document.sections()) {
            if (section.text().isBlank()) {
                continue;
            }
            var split = splitSection(section.text());
            for (var part : split) {
                if (chunks.size() >= maxChunks) {
                    throw new IllegalArgumentException("chunk count exceeds the maximum");
                }
                chunks.add(new KnowledgeChunk(
                        ordinal++,
                        section.headingPath(),
                        part.text(),
                        globalOffset + part.start(),
                        globalOffset + part.end()));
            }
            globalOffset += countCodePoints(section.text());
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("document contains no chunkable text");
        }
        return List.copyOf(chunks);
    }

    public int countCodePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    private List<Part> splitSection(String text) {
        int[] points = text.codePoints().toArray();
        var parts = new ArrayList<Part>();
        int start = 0;
        while (start < points.length) {
            int end = Math.min(start + maxChunkCodePoints, points.length);
            if (end < points.length) {
                end = findBoundary(points, start, end);
            }
            parts.add(new Part(
                    start,
                    end,
                    new String(points, start, end - start)));
            if (end >= points.length) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return List.copyOf(parts);
    }

    private static int findBoundary(int[] points, int start, int end) {
        int boundary = end;
        for (int i = end; i > start; i--) {
            int point = points[i - 1];
            if (point == '\n') {
                return i;
            }
        }
        for (int i = end; i > start; i--) {
            int point = points[i - 1];
            if (isSentenceEnd(point)) {
                return i;
            }
        }
        for (int i = end; i > start; i--) {
            int point = points[i - 1];
            if (Character.isWhitespace(point)) {
                return i;
            }
        }
        return boundary;
    }

    private static boolean isSentenceEnd(int point) {
        return point == '。' || point == '！' || point == '？'
                || point == '.' || point == '!' || point == '?';
    }

    private record Part(int start, int end, String text) {
    }
}
