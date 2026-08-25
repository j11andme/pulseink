package com.pulseink.client.mcp;

import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolProvider;
import com.pulseink.agent.tool.ToolResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configured Stdio MCP adapter. The launch command is selected from a trusted, constructor-time
 * allowlist and is never derived from a {@link ToolCall}. Process creation and MCP protocol I/O are
 * delegated to an injected {@link McpSession}; this provider itself cannot download or execute an
 * arbitrary program.
 */
public final class StdioMcpToolProvider implements ToolProvider {

    private final String namespace;
    private final TrustedCommand command;
    private final boolean enabled;
    private final Duration discoveryTtl;
    private final Clock clock;
    private final McpSession session;
    private DiscoveryCache cache;

    public StdioMcpToolProvider(
            String namespace,
            TrustedCommand command,
            Set<TrustedCommand> allowedCommands,
            boolean enabled,
            Duration discoveryTtl,
            Clock clock,
            McpSession session) {
        this.namespace = ToolDefinition.requireValidNamespace(namespace);
        this.command = Objects.requireNonNull(command, "command must not be null");
        var trusted = Set.copyOf(
                Objects.requireNonNull(allowedCommands, "allowedCommands must not be null"));
        if (!trusted.contains(command)) {
            throw new IllegalArgumentException("stdio command is not in the trusted allowlist");
        }
        this.enabled = enabled;
        this.discoveryTtl = requirePositive(discoveryTtl, "discoveryTtl");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.session = Objects.requireNonNull(session, "session must not be null");
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public synchronized List<ToolDefinition> discover() {
        if (!enabled) {
            return List.of();
        }
        Instant now = clock.instant();
        if (cache != null && now.isBefore(cache.discoveredAt().plus(discoveryTtl))) {
            return cache.definitions();
        }
        var definitions = validateDefinitions(session.discover(command));
        cache = new DiscoveryCache(definitions, now);
        return definitions;
    }

    @Override
    public ToolResult invoke(ToolCall call, Duration timeout) {
        if (!enabled) {
            throw new IllegalStateException("stdio MCP provider is disabled");
        }
        requireDiscovered(call);
        return session.invoke(command, call, timeout);
    }

    private synchronized void requireDiscovered(ToolCall call) {
        boolean known = discover().stream()
                .anyMatch(definition -> definition.qualifiedName().equals(call.qualifiedName()));
        if (!known) {
            throw new IllegalStateException("unknown tool: " + call.qualifiedName());
        }
    }

    private List<ToolDefinition> validateDefinitions(List<ToolDefinition> discovered) {
        var copy = new ArrayList<ToolDefinition>();
        var names = new HashSet<String>();
        for (var definition : List.copyOf(
                Objects.requireNonNull(discovered, "discovered tools must not be null"))) {
            if (!namespace.equals(definition.namespace())) {
                throw new IllegalArgumentException(
                        "namespace mismatch for " + definition.qualifiedName());
            }
            if (!names.add(definition.qualifiedName())) {
                throw new IllegalArgumentException(
                        "duplicate tool: " + definition.qualifiedName());
            }
            copy.add(definition);
        }
        return List.copyOf(copy);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    /** Exact executable and argument vector allowed by checked-in configuration. */
    public record TrustedCommand(String executable, List<String> arguments) {
        public TrustedCommand {
            Objects.requireNonNull(executable, "executable must not be null");
            if (executable.isBlank()) {
                throw new IllegalArgumentException("executable must not be blank");
            }
            arguments = List.copyOf(
                    Objects.requireNonNull(arguments, "arguments must not be null"));
            if (arguments.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("arguments must not contain null");
            }
        }
    }

    /** Trusted MCP protocol/session boundary supplied by application configuration. */
    public interface McpSession {
        List<ToolDefinition> discover(TrustedCommand command);

        ToolResult invoke(TrustedCommand command, ToolCall call, Duration timeout);
    }

    private record DiscoveryCache(List<ToolDefinition> definitions, Instant discoveredAt) {
    }
}
