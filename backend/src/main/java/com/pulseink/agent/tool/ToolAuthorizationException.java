package com.pulseink.agent.tool;

/**
 * Thrown when a {@link ToolCall} is rejected before reaching a {@link ToolProvider}: the tool is
 * not in the profile allowlist, the risk level requires approval that was not granted, the
 * arguments fail JSON-Schema validation, or the timeout is invalid.
 */
public class ToolAuthorizationException extends RuntimeException {

    public ToolAuthorizationException(String message) {
        super(message);
    }
}
