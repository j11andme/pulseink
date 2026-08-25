package com.pulseink.agent.tool;

import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.AgentRole;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Java-only policy boundary enforced before and after every {@link ToolProvider} invocation.
 * The policy checks the profile allowlist, risk/approval state, JSON-Schema subset, timeout
 * range and response-size limit using plain Java data structures; it never calls a Provider.
 */
public final class ToolPolicy {

    private static final Duration DEFAULT_MAX_TIMEOUT = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_RESPONSE_BYTES = 1_048_576;

    private final AgentRole role;
    private final Set<String> allowedTools;
    private final Duration maxTimeout;
    private final int maxResponseBytes;

    private ToolPolicy(AgentRole role, Set<String> allowedTools,
                       Duration maxTimeout, int maxResponseBytes) {
        this.role = role;
        this.allowedTools = Set.copyOf(
                Objects.requireNonNull(allowedTools, "allowedTools must not be null"));
        Objects.requireNonNull(maxTimeout, "maxTimeout must not be null");
        if (maxTimeout.isZero() || maxTimeout.isNegative()) {
            throw new IllegalArgumentException("maxTimeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        this.maxTimeout = maxTimeout;
        this.maxResponseBytes = maxResponseBytes;
    }

    public static ToolPolicy forProfile(AgentProfile profile) {
        return new ToolPolicy(null, profile.allowedTools(),
                DEFAULT_MAX_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public static ToolPolicy forRole(AgentRole role, Set<String> allowedTools) {
        return new ToolPolicy(role, allowedTools, DEFAULT_MAX_TIMEOUT, DEFAULT_MAX_RESPONSE_BYTES);
    }

    public ToolPolicy withMaxTimeout(Duration maxTimeout) {
        return new ToolPolicy(role, allowedTools, maxTimeout, maxResponseBytes);
    }

    public ToolPolicy withMaxResponseBytes(int maxResponseBytes) {
        return new ToolPolicy(role, allowedTools, maxTimeout, maxResponseBytes);
    }

    /**
     * Pre-invocation authorization: allowlist, risk/approval, and JSON-Schema validation.
     */
    public void authorize(ToolDefinition definition, ToolCall call, ApprovalState approval) {
        if (!definition.qualifiedName().equals(call.qualifiedName())) {
            throw new ToolAuthorizationException(
                    "call qualifiedName does not match definition: "
                            + call.qualifiedName() + " vs " + definition.qualifiedName());
        }
        if (!allowedTools.contains(definition.qualifiedName())) {
            throw new ToolAuthorizationException(
                    "tool not in allowlist: " + definition.qualifiedName());
        }
        ToolRisk risk = definition.risk();
        if ((risk == ToolRisk.EXTERNAL_SIDE_EFFECT || risk == ToolRisk.SECRET)
                && approval != ApprovalState.APPROVED) {
            throw new ToolAuthorizationException(
                    "approval required for risk " + risk + " on " + definition.qualifiedName());
        }
        validateSchema(definition.schema(), call.arguments());
    }

    /**
     * Pre-invocation timeout validation: null, zero, negative or above the policy maximum.
     */
    public void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(maxTimeout) > 0) {
            throw new ToolAuthorizationException("invalid timeout: " + timeout);
        }
    }

    /**
     * Post-invocation response-size validation.
     */
    public void validateResponseSize(ToolResult result) {
        if (result.content().length > maxResponseBytes) {
            throw new ToolInvocationException(
                    "response exceeds max bytes: " + result.content().length
                            + " > " + maxResponseBytes);
        }
    }

    private void validateSchema(ToolDefinition.Schema schema, Map<String, Object> arguments) {
        for (String required : schema.required()) {
            if (!arguments.containsKey(required)) {
                throw new ToolAuthorizationException(
                        "missing required argument: " + required);
            }
        }
        var properties = schema.properties();
        for (var entry : arguments.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            var spec = properties.get(key);
            if (spec == null) {
                if (!schema.additionalProperties()) {
                    throw new ToolAuthorizationException(
                            "additional property not allowed: " + key);
                }
                continue;
            }
            validateType(spec, key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private void validateType(ToolDefinition.PropertySpec spec, String key, Object value) {
        if (value == null) {
            throw typeError(key, spec.type(), null);
        }
        String type = spec.type();
        switch (type) {
            case "string" -> {
                if (!(value instanceof String)) throw typeError(key, type, value);
            }
            case "integer" -> {
                if (!(value instanceof Integer || value instanceof Long)) throw typeError(key, type, value);
            }
            case "number" -> {
                if (!(value instanceof Number)) throw typeError(key, type, value);
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) throw typeError(key, type, value);
            }
            case "array" -> {
                if (!(value instanceof java.util.List<?>)) throw typeError(key, type, value);
            }
            case "object" -> {
                if (!(value instanceof Map<?, ?> map)) throw typeError(key, type, value);
                if (spec.schema() != null) {
                    validateSchema(spec.schema(), (Map<String, Object>) map);
                }
            }
            default -> throw new ToolAuthorizationException(
                    "unsupported JSON Schema type: " + type);
        }
    }

    private ToolAuthorizationException typeError(String key, String expected, Object value) {
        return new ToolAuthorizationException(
                "argument '" + key + "' must be " + expected
                        + ", got " + (value == null ? "null" : value.getClass().getSimpleName()));
    }
}
