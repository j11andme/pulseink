package com.pulseink.client.mcp;

import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolProvider;
import com.pulseink.agent.tool.ToolResult;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configured Streamable HTTP MCP adapter. The remote endpoint must exactly match a trusted
 * constructor-time allowlist; tool arguments cannot override it. Network/protocol I/O is delegated
 * to an injected {@link McpTransport}, keeping discovery and invocation deterministic in tests.
 */
public final class StreamableHttpMcpToolProvider implements ToolProvider {

    private final String namespace;
    private final TrustedEndpoint endpoint;
    private final boolean enabled;
    private final Duration discoveryTtl;
    private final Clock clock;
    private final McpTransport transport;
    private DiscoveryCache cache;

    public StreamableHttpMcpToolProvider(
            String namespace,
            TrustedEndpoint endpoint,
            Set<TrustedEndpoint> allowedEndpoints,
            boolean enabled,
            Duration discoveryTtl,
            Clock clock,
            McpTransport transport) {
        this.namespace = ToolDefinition.requireValidNamespace(namespace);
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        var trusted = Set.copyOf(
                Objects.requireNonNull(allowedEndpoints, "allowedEndpoints must not be null"));
        if (!trusted.contains(endpoint)) {
            throw new IllegalArgumentException("MCP endpoint is not in the trusted allowlist");
        }
        this.enabled = enabled;
        this.discoveryTtl = requirePositive(discoveryTtl, "discoveryTtl");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
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
        var definitions = validateDefinitions(transport.discover(endpoint));
        cache = new DiscoveryCache(definitions, now);
        return definitions;
    }

    @Override
    public ToolResult invoke(ToolCall call, Duration timeout) {
        if (!enabled) {
            throw new IllegalStateException("Streamable HTTP MCP provider is disabled");
        }
        requireDiscovered(call);
        return transport.invoke(endpoint, call, timeout);
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

    /** Exact HTTP(S) endpoint allowed by checked-in application configuration. */
    public record TrustedEndpoint(String url) {
        public TrustedEndpoint {
            Objects.requireNonNull(url, "url must not be null");
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("MCP endpoint must be a valid URI", exception);
            }
            if (!("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "MCP endpoint must be an absolute HTTP(S) URI without credentials or fragment");
            }
        }
    }

    /** Trusted MCP HTTP protocol boundary supplied by application configuration. */
    public interface McpTransport {
        List<ToolDefinition> discover(TrustedEndpoint endpoint);

        ToolResult invoke(TrustedEndpoint endpoint, ToolCall call, Duration timeout);
    }

    private record DiscoveryCache(List<ToolDefinition> definitions, Instant discoveredAt) {
    }
}
