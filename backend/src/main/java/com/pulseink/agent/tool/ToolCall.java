package com.pulseink.agent.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable request to invoke a tool. The {@code qualifiedName} follows the same character rules
 * as {@link ToolDefinition}; {@code arguments} is deep-copied into an unmodifiable structure so
 * callers and the model can never mutate what the Provider receives.
 */
public final class ToolCall {

    private static final Pattern QUALIFIED_NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*$");

    public static final int MAX_ARGUMENT_COUNT = 256;

    private final String qualifiedName;
    private final Map<String, Object> arguments;

    private ToolCall(String qualifiedName, Map<String, Object> arguments) {
        if (qualifiedName == null) {
            throw new IllegalArgumentException("qualifiedName must not be null");
        }
        if (!QUALIFIED_NAME_PATTERN.matcher(qualifiedName).matches()) {
            throw new IllegalArgumentException("qualifiedName is invalid: " + qualifiedName);
        }
        if (arguments == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        if (arguments.size() > MAX_ARGUMENT_COUNT) {
            throw new IllegalArgumentException(
                    "arguments count " + arguments.size()
                            + " exceeds limit " + MAX_ARGUMENT_COUNT);
        }
        this.qualifiedName = qualifiedName;
        this.arguments = immutableArguments(arguments);
    }

    public static ToolCall of(String qualifiedName, Map<String, Object> arguments) {
        return new ToolCall(qualifiedName, arguments);
    }

    public String qualifiedName() {
        return qualifiedName;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    private static Map<String, Object> immutableArguments(Map<String, Object> arguments) {
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : arguments.entrySet()) {
            copy.put(entry.getKey(), deepImmutable(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object deepImmutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepImmutable(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>();
            for (var element : list) {
                copy.add(deepImmutable(element));
            }
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            var copy = new HashSet<Object>();
            for (var element : set) {
                copy.add(deepImmutable(element));
            }
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        return value;
    }
}
