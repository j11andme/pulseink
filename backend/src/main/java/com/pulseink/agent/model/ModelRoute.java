package com.pulseink.agent.model;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable model route chosen by {@link ModelRouter}: the concrete provider, model id,
 * capabilities and the port to call. Never carries API keys or base URLs.
 */
public record ModelRoute(
        String providerId,
        String modelId,
        Set<ModelCapability> capabilities,
        AgentModelPort modelPort) {

    public ModelRoute {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        capabilities = Set.copyOf(Objects.requireNonNull(
                capabilities, "capabilities must not be null"));
        modelPort = Objects.requireNonNull(modelPort, "modelPort must not be null");
    }
}
