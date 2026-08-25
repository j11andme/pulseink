package com.pulseink.client.tool;

import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolProvider;
import com.pulseink.agent.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * OpenAPI-derived {@link ToolProvider}. Tools are registered at construction from a pre-parsed
 * (simplified) OpenAPI document, so no OpenAPI parsing dependency is introduced and no arbitrary
 * URL forwarding exists. Path templates use {@code {param}} placeholders that are substituted
 * from the {@link ToolCall} arguments before the request is sent through the injected
 * {@link HttpToolProvider.HttpTransport}.
 */
public final class OpenApiToolProvider implements ToolProvider {

    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^{}]+)}");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final String namespace;
    private final String baseUrl;
    private final Map<String, Registration> registrations;
    private final HttpToolProvider.HttpTransport transport;

    public OpenApiToolProvider(String namespace, String baseUrl,
                               List<Registration> registrations,
                               HttpToolProvider.HttpTransport transport) {
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
        String path = resolvePath(registration.path(), call.arguments());
        var request = new HttpToolProvider.HttpRequest(
                registration.method(), baseUrl + path, Map.of(), call.arguments());
        var response = transport.send(request, timeout);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP status " + response.statusCode());
        }
        return ToolResult.of(response.body(), Map.of());
    }

    private static String resolvePath(String template, Map<String, Object> arguments) {
        var matcher = PATH_PARAMETER.matcher(template);
        var resolved = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!arguments.containsKey(name) || arguments.get(name) == null) {
                throw new IllegalArgumentException("missing OpenAPI path parameter: " + name);
            }
            matcher.appendReplacement(
                    resolved,
                    java.util.regex.Matcher.quoteReplacement(
                            encodePathSegment(String.valueOf(arguments.get(name)))));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static String encodePathSegment(String value) {
        var encoded = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int current = raw & 0xFF;
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '-' || current == '.' || current == '_' || current == '~') {
                encoded.append((char) current);
            } else {
                encoded.append('%')
                        .append(HEX[current >>> 4])
                        .append(HEX[current & 0x0F]);
            }
        }
        return encoded.toString();
    }

    /**
     * Immutable pairing of a tool definition with its HTTP verb and OpenAPI path template.
     */
    public record Registration(ToolDefinition definition, String method, String path) {
        public Registration {
            java.util.Objects.requireNonNull(definition, "definition must not be null");
            java.util.Objects.requireNonNull(method, "method must not be null");
            java.util.Objects.requireNonNull(path, "path must not be null");
        }
    }
}
