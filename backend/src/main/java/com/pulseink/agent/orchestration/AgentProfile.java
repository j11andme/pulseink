package com.pulseink.agent.orchestration;

import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelPolicy;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable profile binding logical agent role(s) to a deterministic tool allowlist, model
 * policy, execution budget and, for role profiles, a system prompt, allowed artifact types and
 * local execution limits. The allowlist is the single source of truth for which qualified tool
 * names a role may see; a role can never expand its own allowlist at runtime.
 */
public final class AgentProfile {

    private final String name;
    private final Set<AgentRole> roles;
    private final Set<String> allowedTools;
    private final ModelPolicy modelPolicy;
    private final ExecutionBudget executionBudget;
    private final String systemPrompt;
    private final Set<ArtifactType> allowedArtifactTypes;
    private final Integer maxModelCalls;
    private final Integer maxToolCalls;
    private final Integer maxReactRounds;

    private AgentProfile(String name, Set<AgentRole> roles, Set<String> allowedTools,
                         ModelPolicy modelPolicy, ExecutionBudget executionBudget,
                         String systemPrompt, Set<ArtifactType> allowedArtifactTypes,
                         Integer maxModelCalls, Integer maxToolCalls, Integer maxReactRounds) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.roles = Set.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
        if (this.roles.isEmpty()) {
            throw new IllegalArgumentException("profile must have at least one role");
        }
        this.allowedTools = Set.copyOf(
                Objects.requireNonNull(allowedTools, "allowedTools must not be null"));
        this.modelPolicy = modelPolicy;
        this.executionBudget = executionBudget;
        this.systemPrompt = systemPrompt;
        this.allowedArtifactTypes = allowedArtifactTypes == null
                ? Set.of()
                : Set.copyOf(allowedArtifactTypes);
        this.maxModelCalls = maxModelCalls;
        this.maxToolCalls = maxToolCalls;
        this.maxReactRounds = maxReactRounds;
    }

    /**
     * Compatibility factory for a single logical role.
     */
    public static AgentProfile of(String name, AgentRole role, Set<String> allowedTools) {
        Objects.requireNonNull(role, "role must not be null");
        return new AgentProfile(name, Set.of(role), allowedTools, null, null,
                null, Set.of(), null, null, null);
    }

    /**
     * Unified single-agent profile combining the capabilities of all five logical roles without
     * adding a sixth {@link AgentRole} enum value.
     */
    public static AgentProfile unified(String name, Set<String> allowedTools,
                                       ModelPolicy modelPolicy,
                                       ExecutionBudget executionBudget) {
        return unified(name, allowedTools, modelPolicy, executionBudget, null, Set.of());
    }

    public static AgentProfile unified(String name, Set<String> allowedTools,
                                       ModelPolicy modelPolicy,
                                       ExecutionBudget executionBudget,
                                       String systemPrompt,
                                       Set<ArtifactType> allowedArtifactTypes) {
        Objects.requireNonNull(modelPolicy, "modelPolicy must not be null");
        Objects.requireNonNull(executionBudget, "executionBudget must not be null");
        return new AgentProfile(
                name,
                Set.of(AgentRole.PLANNER, AgentRole.RESEARCHER, AgentRole.STRATEGIST,
                        AgentRole.CREATOR, AgentRole.REVIEWER),
                allowedTools,
                modelPolicy,
                executionBudget,
                systemPrompt, allowedArtifactTypes, null, null, null);
    }

    /**
     * Role factory used by {@code RoleProfileFactory}: full role definition with prompt,
     * allowed outputs and local limits.
     */
    public static AgentProfile role(String name, AgentRole role, Set<String> allowedTools,
                                    ModelPolicy modelPolicy, ExecutionBudget executionBudget,
                                    String systemPrompt, Set<ArtifactType> allowedArtifactTypes,
                                    int maxModelCalls, int maxToolCalls, int maxReactRounds) {
        return new AgentProfile(name, Set.of(role), allowedTools, modelPolicy, executionBudget,
                systemPrompt, allowedArtifactTypes, maxModelCalls, maxToolCalls, maxReactRounds);
    }

    public String name() {
        return name;
    }

    /**
     * Single-role accessor; only valid when the profile was created with {@link #of} or
     * {@link #role}.
     */
    public AgentRole role() {
        if (roles.size() != 1) {
            throw new IllegalStateException(
                    "profile " + name + " has " + roles.size() + " roles, not exactly one");
        }
        return roles.iterator().next();
    }

    public Set<AgentRole> roles() {
        return roles;
    }

    public Set<String> allowedTools() {
        return allowedTools;
    }

    public ModelPolicy modelPolicy() {
        return modelPolicy;
    }

    public ExecutionBudget executionBudget() {
        return executionBudget;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public Set<ArtifactType> allowedArtifactTypes() {
        return allowedArtifactTypes;
    }

    public Integer maxModelCalls() {
        return maxModelCalls;
    }

    public Integer maxToolCalls() {
        return maxToolCalls;
    }

    public Integer maxReactRounds() {
        return maxReactRounds;
    }

    public AgentProfile restrictToolsTo(Set<String> permittedTools) {
        Objects.requireNonNull(permittedTools, "permittedTools must not be null");
        var restricted = new java.util.HashSet<>(allowedTools);
        restricted.retainAll(permittedTools);
        return new AgentProfile(name, roles, restricted, modelPolicy, executionBudget,
                systemPrompt, allowedArtifactTypes, maxModelCalls, maxToolCalls,
                maxReactRounds);
    }
}
