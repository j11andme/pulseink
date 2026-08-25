package com.pulseink.agent.tool;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable description of a tool exposed to the governed registry. The qualified name is
 * {@code namespace.localName}; both segments follow the same character rules so tool names are
 * collision-free across providers.
 */
public final class ToolDefinition {

    private static final Pattern NAME_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*$");

    private final String namespace;
    private final String name;
    private final String description;
    private final Schema schema;
    private final ToolRisk risk;

    private ToolDefinition(String namespace, String name, String description, Schema schema, ToolRisk risk) {
        this.namespace = requireValidName(namespace, "namespace");
        this.name = requireValidName(name, "local name");
        this.description = requireNonBlank(description, "description");
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
        this.risk = Objects.requireNonNull(risk, "risk must not be null");
    }

    public static ToolDefinition of(String namespace, String name, String description, Schema schema, ToolRisk risk) {
        return new ToolDefinition(namespace, name, description, schema, risk);
    }

    public static String requireValidNamespace(String namespace) {
        return requireValidName(namespace, "namespace");
    }

    private static String requireValidName(String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        if (!NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " is invalid: " + value);
        }
        return value;
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    public String namespace() {
        return namespace;
    }

    public String name() {
        return name;
    }

    public String qualifiedName() {
        return namespace + "." + name;
    }

    public String description() {
        return description;
    }

    public Schema schema() {
        return schema;
    }

    public ToolRisk risk() {
        return risk;
    }

    /**
     * Deterministic JSON-Schema subset describing the arguments a tool accepts. Only
     * {@code required}, {@code type} and {@code additionalProperties} are modelled; nested
     * objects reuse {@link Schema} via {@link PropertySpec#object(Schema)}.
     */
    public static final class Schema {
        public static final Schema EMPTY = new Schema(Map.of(), Set.of(), false);

        private final Map<String, PropertySpec> properties;
        private final Set<String> required;
        private final boolean additionalProperties;

        private Schema(Map<String, PropertySpec> properties, Set<String> required, boolean additionalProperties) {
            this.properties = Map.copyOf(properties);
            this.required = Set.copyOf(required);
            this.additionalProperties = additionalProperties;
            for (String key : this.required) {
                if (!this.properties.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "required key '" + key + "' has no property definition");
                }
            }
        }

        public static Schema empty() {
            return EMPTY;
        }

        public static Schema of(Map<String, PropertySpec> properties, Set<String> required,
                                boolean additionalProperties) {
            return new Schema(properties, required, additionalProperties);
        }

        public Map<String, PropertySpec> properties() {
            return properties;
        }

        public Set<String> required() {
            return required;
        }

        public boolean additionalProperties() {
            return additionalProperties;
        }
    }

    /**
     * Type specification of a single schema property. Scalar types use {@link #of(String)};
     * object types embed a nested {@link Schema} via {@link #object(Schema)}.
     */
    public static final class PropertySpec {
        private static final Set<String> SUPPORTED_TYPES = Set.of(
                "string", "integer", "number", "boolean", "array", "object");

        private final String type;
        private final Schema schema;

        private PropertySpec(String type, Schema schema) {
            this.type = type;
            this.schema = schema;
        }

        public static PropertySpec of(String type) {
            Objects.requireNonNull(type, "type must not be null");
            if (!SUPPORTED_TYPES.contains(type)) {
                throw new IllegalArgumentException("unsupported JSON Schema type: " + type);
            }
            return new PropertySpec(type, null);
        }

        public static PropertySpec object(Schema schema) {
            Objects.requireNonNull(schema, "schema must not be null");
            return new PropertySpec("object", schema);
        }

        public String type() {
            return type;
        }

        public Schema schema() {
            return schema;
        }
    }
}
