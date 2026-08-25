package com.pulseink.agent.tool;

import java.time.Duration;
import java.util.List;

/**
 * SPI implemented by every tool source (Java, HTTP/OpenAPI, configured Stdio/Streamable-HTTP MCP).
 * A Provider publishes its namespace and discovered tools, and invokes a single {@link ToolCall}
 * within the supplied {@code timeout}. Implementations must be deterministic and testable with
 * fakes; they must never connect to a real model, MCP server or external account during tests.
 */
public interface ToolProvider {

    String namespace();

    List<ToolDefinition> discover();

    ToolResult invoke(ToolCall call, Duration timeout);
}
