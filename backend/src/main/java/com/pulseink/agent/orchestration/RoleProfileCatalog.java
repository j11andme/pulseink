package com.pulseink.agent.orchestration;

import com.pulseink.agent.artifact.ArtifactType;
import java.util.Set;

/**
 * Catalog of the five fixed role profile definitions, loaded once at startup.
 */
public interface RoleProfileCatalog {

    RoleProfileDefinition forRole(com.pulseink.agent.orchestration.AgentRole role);

    java.util.List<RoleProfileDefinition> allDefinitions();

    /**
     * Immutable role definition loaded from YAML. Never carries provider ids or secrets.
     */
    record RoleProfileDefinition(
            String name,
            com.pulseink.agent.orchestration.AgentRole role,
            String systemPromptVersion,
            String systemPrompt,
            Set<String> toolAllowlist,
            Set<ArtifactType> allowedArtifactTypes,
            int maxModelCalls,
            int maxToolCalls,
            int maxReactRounds) {
    }
}
