package com.pulseink.agent.tool;

/**
 * Thrown when a {@link ToolProvider} invocation fails or its response exceeds the configured size
 * limit. Provider exceptions are normalized via {@link #from(RuntimeException)} so that tokens,
 * Authorization headers, passwords and internal stack traces never reach the caller.
 */
public class ToolInvocationException extends RuntimeException {

    public ToolInvocationException(String message) {
        super(message);
    }

    /**
     * Normalizes a Provider exception into a {@code ToolInvocationException} whose message contains
     * only the exception class name, never the original message, cause or stack trace.
     */
    public static ToolInvocationException from(RuntimeException cause) {
        return new ToolInvocationException(
                "tool invocation failed: " + cause.getClass().getName());
    }
}
