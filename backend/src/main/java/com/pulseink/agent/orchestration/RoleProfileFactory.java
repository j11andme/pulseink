package com.pulseink.agent.orchestration;

import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.orchestration.RoleProfileCatalog.RoleProfileDefinition;
import java.time.Instant;
import java.util.Objects;

/**
 * Materializes immutable {@link AgentProfile}s from role definitions, injecting the shared
 * model policy and a per-role budget slice with a unified deadline.
 */
public final class RoleProfileFactory {

    private final RoleProfileCatalog catalog;

    public RoleProfileFactory(RoleProfileCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
    }

    public AgentProfile forRole(AgentRole role, ModelPolicy modelPolicy,
                                 Instant deadline, long maxTotalTokens) {
        var definition = catalog.forRole(role);
        return materialize(definition, modelPolicy, deadline, maxTotalTokens,
                definition.maxModelCalls());
    }

    private static AgentProfile materialize(RoleProfileDefinition definition,
                                            ModelPolicy modelPolicy,
                                            Instant deadline,
                                            long maxTotalTokens,
                                            int maxModelCalls) {
        var budget = new ExecutionBudget(
                maxModelCalls,
                definition.maxToolCalls(),
                maxTotalTokens,
                definition.maxReactRounds(),
                1,
                deadline);
        return AgentProfile.role(
                definition.name(),
                definition.role(),
                definition.toolAllowlist(),
                modelPolicy,
                budget,
                definition.systemPrompt(),
                definition.allowedArtifactTypes(),
                maxModelCalls,
                definition.maxToolCalls(),
                definition.maxReactRounds());
    }

    public AgentProfile forRole(AgentRole role, ModelPolicy modelPolicy,
                                 Instant deadline, long maxTotalTokens,
                                 int maxModelCallsOverride) {
        if (maxModelCallsOverride <= 0) {
            throw new IllegalArgumentException("maxModelCallsOverride must be positive");
        }
        return materialize(catalog.forRole(role), modelPolicy, deadline, maxTotalTokens,
                maxModelCallsOverride);
    }
}
