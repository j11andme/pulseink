package com.pulseink.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.AgentRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModelRouterTest {

    private static final AgentModelPort MODEL = (request, events) -> () -> {};

    private ModelRoute route(String providerId, String modelId,
                             Set<ModelCapability> capabilities) {
        return new ModelRoute(providerId, modelId, capabilities, MODEL);
    }

    @Test
    void routesOrderedPrimaryPerProfilePolicy() {
        var router = new ModelRouter(List.of(
                route("fake", "pulseink-fake", Set.of(ModelCapability.TOOL_FAST)),
                route("ark", "doubao", Set.of(ModelCapability.TOOL_FAST))));
        var profile = AgentProfile.unified(
                "unified",
                Set.of("builtin.deterministic_validate"),
                new ModelPolicy(List.of("fake", "ark"), Set.of(ModelCapability.TOOL_FAST)),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));

        var selected = router.route(profile, Set.of());
        assertThat(selected.providerId()).isEqualTo("fake");
        assertThat(selected.modelId()).isEqualTo("pulseink-fake");
        assertThat(selected.capabilities()).contains(ModelCapability.TOOL_FAST);
    }

    @Test
    void excludedPrimarySelectsFallback() {
        var router = new ModelRouter(List.of(
                route("fake", "pulseink-fake", Set.of(ModelCapability.TOOL_FAST)),
                route("ark", "doubao", Set.of(ModelCapability.TOOL_FAST))));
        var profile = AgentProfile.unified(
                "unified",
                Set.of(),
                new ModelPolicy(List.of("fake", "ark"), Set.of(ModelCapability.TOOL_FAST)),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));

        var selected = router.route(profile, Set.of("fake"));
        assertThat(selected.providerId()).isEqualTo("ark");
    }

    @Test
    void skipsProvidersWithoutRequiredCapability() {
        var router = new ModelRouter(List.of(
                route("fake", "pulseink-fake", Set.of(ModelCapability.TOOL_FAST)),
                route("ark", "doubao", Set.of(ModelCapability.REASONING_STRONG))));
        var profile = AgentProfile.unified(
                "unified",
                Set.of(),
                new ModelPolicy(List.of("fake", "ark"),
                        Set.of(ModelCapability.REASONING_STRONG)),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));

        var selected = router.route(profile, Set.of());
        assertThat(selected.providerId()).isEqualTo("ark");
    }

    @Test
    void failsStablyWhenAllProvidersExcluded() {
        var router = new ModelRouter(List.of(
                route("fake", "pulseink-fake", Set.of(ModelCapability.TOOL_FAST))));
        var profile = AgentProfile.unified(
                "unified",
                Set.of(),
                new ModelPolicy(List.of("fake"), Set.of(ModelCapability.TOOL_FAST)),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));

        assertThatThrownBy(() -> router.route(profile, Set.of("fake")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unifiedProfileDoesNotAddSixthAgentRole() {
        var profile = AgentProfile.unified(
                "unified",
                Set.of("builtin.deterministic_validate"),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));

        assertThat(profile.roles()).containsExactlyInAnyOrder(
                AgentRole.PLANNER, AgentRole.RESEARCHER, AgentRole.STRATEGIST,
                AgentRole.CREATOR, AgentRole.REVIEWER);
        assertThat(AgentRole.values()).hasSize(5);
    }

    @Test
    void singleRoleProfileStillWorksViaLegacyFactory() {
        var profile = AgentProfile.of(
                "researcher", AgentRole.RESEARCHER, Set.of("builtin.search"));
        assertThat(profile.role()).isEqualTo(AgentRole.RESEARCHER);
        assertThat(profile.roles()).containsExactly(AgentRole.RESEARCHER);
    }

    @Test
    void routeExposesPortAndImmutableCapabilities() {
        var route = route("fake", "pulseink-fake", Set.of(ModelCapability.TOOL_FAST));
        assertThat(route.modelPort()).isSameAs(MODEL);
        assertThatThrownBy(() -> route.capabilities().add(ModelCapability.REVIEW_STRICT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void policyOrderIsDeterministicNotHashOrder() {
        var policy = new ModelPolicy(List.of("primary", "fallback"),
                Set.of(ModelCapability.TOOL_FAST));
        assertThat(policy.providerIds()).containsExactly("primary", "fallback");
    }
}
