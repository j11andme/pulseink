package com.pulseink.client.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.plan.PlanParser;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.agent.plan.PlanTask;
import com.pulseink.agent.plan.PlanTaskAccess;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Strict Jackson-based plan parser. Unknown fields at any level are rejected.
 */
public final class JacksonPlanParser implements PlanParser {

    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "tasks");
    private static final Set<String> TASK_FIELDS = Set.of(
            "taskId", "role", "objective", "dependsOn", "requiredArtifactTypes",
            "outputArtifactType", "access");
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public PlanSpec parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            throw new IllegalArgumentException("model output must not be blank");
        }
        if (modelOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("model output exceeds size limit");
        }
        String cleaned = StrictJsonEnvelope.unwrap(modelOutput);
        JsonNode root;
        try {
            root = mapper.readTree(cleaned);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("model output is not valid JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("model output must be a JSON object");
        }
        rejectUnknown(root, ROOT_FIELDS, "root");
        if (!root.has("schemaVersion") || !root.get("schemaVersion").isIntegralNumber()) {
            throw new IllegalArgumentException("schemaVersion must be an integer");
        }
        int schemaVersion = root.get("schemaVersion").asInt();
        var tasksNode = root.get("tasks");
        if (tasksNode == null || !tasksNode.isArray()) {
            throw new IllegalArgumentException("tasks must be an array");
        }
        var tasks = new ArrayList<PlanTask>();
        for (JsonNode taskNode : tasksNode) {
            if (!taskNode.isObject()) {
                throw new IllegalArgumentException("task must be a JSON object");
            }
            rejectUnknown(taskNode, TASK_FIELDS, "task");
            tasks.add(new PlanTask(
                    requiredText(taskNode, "taskId"),
                    enumValue(taskNode, "role", AgentRole.class),
                    requiredText(taskNode, "objective"),
                    stringArray(taskNode, "dependsOn"),
                    artifactSet(taskNode, "requiredArtifactTypes"),
                    enumValue(taskNode, "outputArtifactType", ArtifactType.class),
                    enumValue(taskNode, "access", PlanTaskAccess.class)));
        }
        return new PlanSpec(schemaVersion, List.copyOf(tasks));
    }

    private static String requiredText(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a non-null string");
        }
        String text = value.textValue();
        if (text.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return text;
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type) {
        String value = requiredText(node, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown " + field + ": " + value);
        }
    }

    private static List<String> stringArray(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        var result = new ArrayList<String>();
        for (JsonNode element : value) {
            if (!element.isTextual() || element.textValue().isBlank()) {
                throw new IllegalArgumentException(field + " must contain non-blank strings");
            }
            result.add(element.textValue());
        }
        return List.copyOf(result);
    }

    private static Set<ArtifactType> artifactSet(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null) {
            return Set.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        var result = new ArrayList<ArtifactType>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw new IllegalArgumentException(field + " must contain strings");
            }
            try {
                result.add(ArtifactType.valueOf(element.textValue()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        "unknown artifact type in " + field + ": " + element.textValue());
            }
        }
        return Set.copyOf(result);
    }

    private static void rejectUnknown(JsonNode node, Set<String> known, String path) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!known.contains(name)) {
                throw new IllegalArgumentException("unknown field at " + path + ": " + name);
            }
        }
    }
}
