package com.pulseink.client.tool;

import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolProvider;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in Java {@link ToolProvider}. Handlers are registered explicitly at construction time;
 * no reflection scanning, model-generated class names or dynamic discovery is used. A handler
 * is a plain Java function ({@link JavaToolHandler}) invoked directly within the governed
 * {@code ToolRegistry.invokeAuthorized} boundary.
 */
public final class JavaToolProvider implements ToolProvider {

    private final String namespace;
    private final Map<String, ToolDefinition> definitions;
    private final Map<String, JavaToolHandler> handlers;

    public JavaToolProvider(String namespace, List<Registration> registrations) {
        this.namespace = ToolDefinition.requireValidNamespace(namespace);
        var defs = new LinkedHashMap<String, ToolDefinition>();
        var hnds = new LinkedHashMap<String, JavaToolHandler>();
        for (var registration : registrations) {
            var definition = registration.definition();
            String qn = definition.qualifiedName();
            if (!definition.namespace().equals(this.namespace)) {
                throw new IllegalArgumentException(
                        "namespace mismatch for " + qn + ": expected " + this.namespace);
            }
            if (defs.containsKey(qn)) {
                throw new IllegalArgumentException("duplicate tool: " + qn);
            }
            defs.put(qn, definition);
            hnds.put(qn, registration.handler());
        }
        this.definitions = Collections.unmodifiableMap(defs);
        this.handlers = Collections.unmodifiableMap(hnds);
    }

    @Override
    public String namespace() {
        return namespace;
    }

    @Override
    public List<ToolDefinition> discover() {
        return List.copyOf(definitions.values());
    }

    @Override
    public ToolResult invoke(ToolCall call, Duration timeout) {
        var handler = handlers.get(call.qualifiedName());
        if (handler == null) {
            throw new IllegalStateException("unknown tool: " + call.qualifiedName());
        }
        return handler.invoke(call, timeout);
    }

    /**
     * Immutable pairing of a {@link ToolDefinition} with its Java handler.
     */
    public record Registration(ToolDefinition definition, JavaToolHandler handler) {
        public Registration {
            java.util.Objects.requireNonNull(definition, "definition must not be null");
            java.util.Objects.requireNonNull(handler, "handler must not be null");
        }
    }

    /**
     * Functional handler invoked synchronously by {@link JavaToolProvider#invoke}.
     */
    @FunctionalInterface
    public interface JavaToolHandler {
        ToolResult invoke(ToolCall call, Duration timeout);
    }
}
