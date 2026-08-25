package com.pulseink.agent.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ordered model selection policy for a profile. The provider id order is authoritative and
 * never derived from map iteration; capabilities narrow the candidate set.
 */
public record ModelPolicy(
        List<String> providerIds,
        Set<ModelCapability> requiredCapabilities) {

    public ModelPolicy {
        providerIds = List.copyOf(Objects.requireNonNull(
                providerIds, "providerIds must not be null"));
        if (providerIds.isEmpty()) {
            throw new IllegalArgumentException("providerIds must not be empty");
        }
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities must not be null"));
    }
}
