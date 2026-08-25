package com.pulseink.agent.tool;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result returned by a {@link ToolProvider}. The content is held as UTF-8 bytes so
 * binary and text results share one representation; both the byte array and metadata map are
 * defensively copied so callers can never mutate the Provider's snapshot.
 */
public final class ToolResult {

    private final byte[] content;
    private final Map<String, String> metadata;

    private ToolResult(byte[] content, Map<String, String> metadata) {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        this.content = content.clone();
        this.metadata = Map.copyOf(metadata);
    }

    public static ToolResult of(String content) {
        Objects.requireNonNull(content, "content must not be null");
        return new ToolResult(content.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public static ToolResult of(byte[] content, Map<String, String> metadata) {
        return new ToolResult(content, metadata);
    }

    public byte[] content() {
        return content.clone();
    }

    public String contentText() {
        return new String(content, StandardCharsets.UTF_8);
    }

    public Map<String, String> metadata() {
        return metadata;
    }
}
