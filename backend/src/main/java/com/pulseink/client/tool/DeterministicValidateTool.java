package com.pulseink.client.tool;

import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolResult;
import java.time.Duration;

/**
 * Deterministic built-in validator registered as {@code builtin.deterministic_validate}.
 * Accepts a single non-blank {@code content} argument and returns a fixed JSON structure.
 * It never touches the network, disk or database.
 */
public final class DeterministicValidateTool {

    public static final String QUALIFIED_NAME = "builtin.deterministic_validate";

    public ToolResult validate(ToolCall call, Duration timeout) {
        var content = call.arguments().get("content");
        if (content == null || !(content instanceof String text) || text.isBlank()) {
            return ToolResult.of(
                    "{\"valid\":false,\"issues\":[\"content must be a non-blank string\"]}");
        }
        if (text.length() > 4000) {
            return ToolResult.of(
                    "{\"valid\":false,\"issues\":[\"content exceeds 4000 characters\"]}");
        }
        return ToolResult.of("{\"valid\":true,\"issues\":[]}");
    }
}
