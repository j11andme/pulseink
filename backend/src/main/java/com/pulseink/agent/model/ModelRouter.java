package com.pulseink.agent.model;

import com.pulseink.agent.orchestration.AgentProfile;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selects the next model route for a profile. Candidates are ordered by the profile's
 * {@link ModelPolicy} provider ids, filtered by exclusions and required capabilities, and the
 * first match wins. The router only selects; retries and fallback are handled by the engine.
 */
public final class ModelRouter {

    private final Map<String, ModelRoute> routesByProvider;

    public ModelRouter(List<ModelRoute> routes) {
        Objects.requireNonNull(routes, "routes must not be null");
        this.routesByProvider = routes.stream().collect(Collectors.toUnmodifiableMap(
                ModelRoute::providerId, Function.identity(), (a, b) -> {
                    throw new IllegalArgumentException(
                            "duplicate route for provider: " + a.providerId());
                }));
    }

    public ModelRoute route(AgentProfile profile, Set<String> excludedProviderIds) {
        Objects.requireNonNull(profile, "profile must not be null");
        var excluded = excludedProviderIds == null ? Set.of() : Set.copyOf(excludedProviderIds);
        var policy = profile.modelPolicy();
        for (String providerId : policy.providerIds()) {
            if (excluded.contains(providerId)) {
                continue;
            }
            var route = routesByProvider.get(providerId);
            if (route == null || !route.capabilities().containsAll(
                    policy.requiredCapabilities())) {
                continue;
            }
            return route;
        }
        throw new IllegalStateException(
                "no model route available for profile " + profile.name());
    }
}
