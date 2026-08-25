package com.pulseink.client.tool;

import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolProvider;
import com.pulseink.agent.tool.ToolResult;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP {@link ToolProvider}. Each registered tool maps to a fixed method and path on a configured
 * base URL. All I/O goes through an injected {@link HttpTransport} so tests run against a
 * deterministic fake; no real URL is accessed and no arbitrary URL forwarding is implemented.
 * Response headers are never copied into the {@link ToolResult}, so sensitive headers stay out
 * of the model-visible metadata.
 */
public final class HttpToolProvider implements ToolProvider {

    private final String namespace;
    private final String baseUrl;
    private final Map<String, Registration> registrations;
    private final HttpTransport transport;

    public HttpToolProvider(String namespace, String baseUrl,
                            List<Registration> registrations, HttpTransport transport) {
        this.namespace = ToolDefinition.requireValidNamespace(namespace);
        this.baseUrl = baseUrl;
        this.transport = transport;
        var map = new LinkedHashMap<String, Registration>();
        for (var registration : registrations) {
            String qn = registration.definition().qualifiedName();
            if (!registration.definition().namespace().equals(this.namespace)) {
                throw new IllegalArgumentException(
                        "namespace mismatch for " + qn + ": expected " + this.namespace);
            }
            if (map.containsKey(qn)) {
                throw new IllegalArgumentException("duplicate tool: " + qn);
            }
            map.put(qn, registration);
        }
        this.registrations = Collections.unmodifiableMap(map);
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public List<ToolDefinition> discover() {
        return registrations.values().stream().map(Registration::definition).toList();
    }

    @Override
    public ToolResult invoke(ToolCall call, Duration timeout) {
        var registration = registrations.get(call.qualifiedName());
        if (registration == null) {
            throw new IllegalStateException("unknown tool: " + call.qualifiedName());
        }
        var request = new HttpRequest(
                registration.method(), baseUrl + registration.path(), Map.of(), call.arguments());
        var response = transport.send(request, timeout);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP status " + response.statusCode());
        }
        return ToolResult.of(response.body(), Map.of());
    }

    /**
     * Immutable pairing of a tool definition with its HTTP verb and path.
     */
    public record Registration(ToolDefinition definition, String method, String path) {
        public Registration {
            java.util.Objects.requireNonNull(definition, "definition must not be null");
            java.util.Objects.requireNonNull(method, "method must not be null");
            java.util.Objects.requireNonNull(path, "path must not be null");
        }
    }

    /**
     * Minimal HTTP abstraction injected by the caller so that tests never touch the network.
     */
    @FunctionalInterface
    public interface HttpTransport {
        HttpResponse send(HttpRequest request, Duration timeout);
    }

    public record HttpRequest(String method, String url,
                              Map<String, String> headers, Map<String, Object> body) {
        public HttpRequest {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? Map.of() : deepImmutable(body);
        }
    }

    public record HttpResponse(int statusCode, byte[] body, Map<String, String> headers) {
        public HttpResponse {
            body = body == null ? new byte[0] : body.clone();
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    private static Map<String, Object> deepImmutable(Map<String, Object> source) {
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : source.entrySet()) {
            copy.put(entry.getKey(), immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new java.util.ArrayList<Object>();
            for (var element : list) {
                copy.add(immutableValue(element));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        return value;
    }
}