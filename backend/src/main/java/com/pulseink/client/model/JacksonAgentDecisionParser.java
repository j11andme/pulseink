package com.pulseink.client.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.react.AgentDecision;
import com.pulseink.agent.react.AgentDecision.ArtifactSpec;
import com.pulseink.agent.react.AgentDecision.FinalDecision;
import com.pulseink.agent.react.AgentDecision.NeedApprovalDecision;
import com.pulseink.agent.react.AgentDecision.ReplanDecision;
import com.pulseink.agent.react.AgentDecision.ToolCallDecision;
import com.pulseink.agent.react.AgentDecisionParser;
import com.pulseink.agent.tool.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict Jackson-based parser for the PulseInk structured decision protocol. Unknown fields,
 * unknown decision/type values, typed nulls, blank summaries, duplicate artifact types and
 * oversized payloads are all rejected; a parse failure never yields a partial decision.
 */
public final class JacksonAgentDecisionParser implements AgentDecisionParser {

    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    static final int MAX_SUMMARY_CHARS = 2000;

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "decision", "decisionSummary", "toolCall", "artifacts");
    private static final Set<String> KNOWN_TOOL_CALL_FIELDS = Set.of(
            "qualifiedName", "arguments");
    private static final Set<String> KNOWN_ARTIFACT_FIELDS = Set.of(
            "type", "content", "sourceRefs");

    private final ObjectMapper mapper;

    public JacksonAgentDecisionParser() {
        this.mapper = new ObjectMapper();
    }

    @Override
    public AgentDecision parse(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            throw new IllegalArgumentException("model output must not be blank");
        }
        if (modelOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("model output exceeds size limit");
        }
        JsonNode root;
        try {
            root = mapper.readTree(StrictJsonEnvelope.unwrap(modelOutput));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("model output is not valid JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("model output must be a JSON object");
        }
        rejectUnknownFields(root, KNOWN_FIELDS, "root");

        var decisionNode = requiredText(root, "decision");
        String summary = requireSummary(root);

        return switch (decisionNode) {
            case "TOOL_CALL" -> toolCallDecision(root, summary);
            case "FINAL" -> finalDecision(root, summary);
            case "REPLAN" -> {
                rejectForbidden(root, Set.of("toolCall", "artifacts"), "REPLAN");
                yield new ReplanDecision(summary);
            }
            case "NEED_APPROVAL" -> {
                rejectForbidden(root, Set.of("toolCall", "artifacts"), "NEED_APPROVAL");
                yield new NeedApprovalDecision(summary);
            }
            default -> throw new IllegalArgumentException(
                    "unknown decision: " + decisionNode);
        };
    }

    private AgentDecision toolCallDecision(JsonNode root, String summary) {
        var toolCallNode = root.get("toolCall");
        if (toolCallNode == null || !toolCallNode.isObject()) {
            throw new IllegalArgumentException("TOOL_CALL requires a toolCall object");
        }
        rejectUnknownFields(toolCallNode, KNOWN_TOOL_CALL_FIELDS, "toolCall");
        if (root.has("artifacts")) {
            throw new IllegalArgumentException("TOOL_CALL must not carry artifacts");
        }
        String qualifiedName = requiredText(toolCallNode, "qualifiedName");
        var argumentsNode = toolCallNode.get("arguments");
        if (argumentsNode == null) {
            throw new IllegalArgumentException("toolCall requires arguments");
        }
        Map<String, Object> arguments = toPlainMap(argumentsNode);
        return new ToolCallDecision(summary, ToolCall.of(qualifiedName, arguments));
    }

    private AgentDecision finalDecision(JsonNode root, String summary) {
        var artifactsNode = root.get("artifacts");
        if (artifactsNode == null || !artifactsNode.isArray() || artifactsNode.isEmpty()) {
            throw new IllegalArgumentException("FINAL requires a non-empty artifacts array");
        }
        if (root.has("toolCall")) {
            throw new IllegalArgumentException("FINAL must not carry a toolCall");
        }
        var specs = new ArrayList<ArtifactSpec>();
        var seenTypes = new HashSet<ArtifactType>();
        for (JsonNode node : artifactsNode) {
            if (!node.isObject()) {
                throw new IllegalArgumentException("artifact must be a JSON object");
            }
            rejectUnknownFields(node, KNOWN_ARTIFACT_FIELDS, "artifact");
            var type = artifactType(requiredText(node, "type"));
            if (!seenTypes.add(type)) {
                throw new IllegalArgumentException("duplicate artifact type: " + type);
            }
            var contentNode = node.get("content");
            if (contentNode == null) {
                throw new IllegalArgumentException("artifact requires content");
            }
            var sourceRefsNode = node.get("sourceRefs");
            List<String> sourceRefs = List.of();
            if (sourceRefsNode != null) {
                if (!sourceRefsNode.isArray()) {
                    throw new IllegalArgumentException("sourceRefs must be an array");
                }
                var refs = new ArrayList<String>();
                for (JsonNode ref : sourceRefsNode) {
                    if (!ref.isTextual() || ref.textValue().isBlank()) {
                        throw new IllegalArgumentException("sourceRefs must be non-blank strings");
                    }
                    refs.add(ref.textValue());
                }
                sourceRefs = List.copyOf(refs);
            }
            specs.add(new ArtifactSpec(type, toPlainMap(contentNode), sourceRefs));
        }
        return new FinalDecision(summary, List.copyOf(specs));
    }

    private static ArtifactType artifactType(String value) {
        try {
            return ArtifactType.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown artifact type: " + value);
        }
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

    private static String requireSummary(JsonNode root) {
        var summaryNode = root.get("decisionSummary");
        if (summaryNode == null || summaryNode.isNull() || !summaryNode.isTextual()) {
            throw new IllegalArgumentException("decisionSummary must be a non-null string");
        }
        String summary = summaryNode.textValue();
        if (summary.isBlank()) {
            throw new IllegalArgumentException("decisionSummary must not be blank");
        }
        if (summary.length() > MAX_SUMMARY_CHARS) {
            throw new IllegalArgumentException(
                    "decisionSummary exceeds " + MAX_SUMMARY_CHARS + " characters");
        }
        return summary;
    }

    private static void rejectForbidden(
            JsonNode root, Set<String> forbidden, String decision) {
        for (String field : forbidden) {
            if (root.has(field)) {
                throw new IllegalArgumentException(
                        decision + " must not carry " + field);
            }
        }
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> known, String path) {
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!known.contains(name)) {
                throw new IllegalArgumentException("unknown field at " + path + ": " + name);
            }
        }
    }

    private static Map<String, Object> toPlainMap(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        var map = new LinkedHashMap<String, Object>();
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            map.put(name, toPlainValue(node.get(name)));
        }
        return Map.copyOf(map);
    }

    private static Object toPlainValue(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            throw new IllegalArgumentException("null JSON values are not allowed");
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isArray()) {
            var list = new ArrayList<Object>();
            for (JsonNode element : node) {
                list.add(toPlainValue(element));
            }
            return List.copyOf(list);
        }
        if (node.isObject()) {
            var map = new LinkedHashMap<String, Object>();
            var names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                map.put(name, toPlainValue(node.get(name)));
            }
            return Map.copyOf(map);
        }
        throw new IllegalArgumentException("unsupported JSON value: " + node.asText());
    }
}
