package com.pulseink.client.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.orchestration.RoleProfileCatalog;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Startup-loaded YAML role profile catalog. Validates exactly five roles, unique names,
 * non-blank prompts and exact tool/output allowlists per the fixed permission table. Unknown
 * YAML fields are rejected; provider ids and secrets are not allowed.
 */
public final class YamlRoleProfileCatalog implements RoleProfileCatalog {

    private static final Map<AgentRole, Set<String>> EXPECTED_TOOLS = Map.of(
            AgentRole.PLANNER, Set.of(),
            AgentRole.RESEARCHER, Set.of("builtin.knowledge_search"),
            AgentRole.STRATEGIST, Set.of(),
            AgentRole.CREATOR, Set.of("builtin.deterministic_validate"),
            AgentRole.REVIEWER, Set.of("builtin.deterministic_validate"));

    private static final Map<AgentRole, Set<ArtifactType>> EXPECTED_OUTPUTS = Map.of(
            AgentRole.PLANNER, Set.of(),
            AgentRole.RESEARCHER, Set.of(ArtifactType.EVIDENCE_PACK),
            AgentRole.STRATEGIST, Set.of(ArtifactType.CONTENT_STRATEGY),
            AgentRole.CREATOR, Set.of(ArtifactType.CONTENT_DRAFT),
            AgentRole.REVIEWER, Set.of(ArtifactType.REVIEW_REPORT));

    private final Map<AgentRole, RoleProfileDefinition> byRole = new HashMap<>();

    public YamlRoleProfileCatalog(String profileDirectory) {
        Objects.requireNonNull(profileDirectory, "profileDirectory must not be null");
        var mapper = new ObjectMapper(new YAMLFactory());
        mapper.configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                true);
        for (var role : AgentRole.values()) {
            String fileName = role.name().toLowerCase(java.util.Locale.ROOT) + "-v1.yaml";
            String resource = profileDirectory + "/" + fileName;
            try (InputStream in = YamlRoleProfileCatalog.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException(
                            "missing role profile resource: " + resource);
                }
                var definition = mapper.readValue(in, YamlDefinition.class);
                var validated = validate(role, definition);
                byRole.put(role, validated);
            } catch (IOException ex) {
                throw new IllegalStateException(
                        "failed to load role profile " + resource, ex);
            }
        }
        if (byRole.size() != 5) {
            throw new IllegalStateException(
                    "role profile catalog must contain exactly five roles");
        }
        long uniqueNames = byRole.values().stream()
                .map(RoleProfileDefinition::name)
                .distinct()
                .count();
        if (uniqueNames != byRole.size()) {
            throw new IllegalStateException("role profile names must be unique");
        }
    }

    @Override
    public RoleProfileDefinition forRole(AgentRole role) {
        var definition = byRole.get(role);
        if (definition == null) {
            throw new IllegalArgumentException("no profile for role " + role);
        }
        return definition;
    }

    @Override
    public List<RoleProfileDefinition> allDefinitions() {
        return java.util.Arrays.stream(AgentRole.values())
                .map(byRole::get)
                .toList();
    }

    private static RoleProfileDefinition validate(AgentRole expectedRole,
                                                  YamlDefinition definition) {
        if (definition.name() == null || definition.name().isBlank()) {
            throw new IllegalStateException(
                    "role profile " + expectedRole + " must declare a name");
        }
        if (definition.role() == null || definition.role() != expectedRole) {
            throw new IllegalStateException(
                    "role profile file must declare role " + expectedRole);
        }
        if (definition.systemPrompt() == null || definition.systemPrompt().isBlank()) {
            throw new IllegalStateException(
                    "role profile " + expectedRole + " must declare a non-blank systemPrompt");
        }
        if (definition.systemPromptVersion() == null
                || definition.systemPromptVersion().isBlank()) {
            throw new IllegalStateException(
                    "role profile " + expectedRole
                            + " must declare a non-blank systemPromptVersion");
        }
        if (definition.maxModelCalls() <= 0
                || definition.maxToolCalls() < 0
                || definition.maxReactRounds() <= 0) {
            throw new IllegalStateException(
                    "role profile " + expectedRole + " has invalid execution limits");
        }
        Set<String> tools = definition.toolAllowlist() == null
                ? Set.of() : definition.toolAllowlist();
        if (!EXPECTED_TOOLS.get(expectedRole).equals(tools)) {
            throw new IllegalStateException(
                    "role profile " + expectedRole + " has unexpected tool allowlist " + tools);
        }
        Set<ArtifactType> outputs = definition.allowedArtifactTypes() == null
                ? Set.of() : definition.allowedArtifactTypes();
        if (!EXPECTED_OUTPUTS.get(expectedRole).equals(outputs)) {
            throw new IllegalStateException(
                    "role profile " + expectedRole + " has unexpected output allowlist " + outputs);
        }
        return new RoleProfileDefinition(
                definition.name(),
                expectedRole,
                definition.systemPromptVersion(),
                definition.systemPrompt(),
                Set.copyOf(tools),
                Set.copyOf(outputs),
                definition.maxModelCalls(),
                definition.maxToolCalls(),
                definition.maxReactRounds());
    }

    /**
     * YAML binding. Unknown fields fail via FAIL_ON_UNKNOWN_PROPERTIES; no provider id or key
     * fields are modeled.
     */
    public record YamlDefinition(
            String name,
            AgentRole role,
            String systemPromptVersion,
            String systemPrompt,
            Set<String> toolAllowlist,
            Set<ArtifactType> allowedArtifactTypes,
            int maxModelCalls,
            int maxToolCalls,
            int maxReactRounds) {
    }
}
