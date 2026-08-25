package com.pulseink.domain.campaign;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record CampaignBrief(
        String objective,
        String audience,
        List<CampaignChannel> channels,
        List<String> constraints) {

    public CampaignBrief {
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("campaign objective must not be blank");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("campaign audience must not be blank");
        }

        channels = List.copyOf(
                Objects.requireNonNull(channels, "channels must not be null"));
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("campaign must target at least one channel");
        }
        Set<CampaignChannel> seen = new HashSet<>();
        for (CampaignChannel channel : channels) {
            if (!seen.add(channel)) {
                throw new IllegalArgumentException("campaign channels must not contain duplicates");
            }
        }

        constraints = List.copyOf(
                Objects.requireNonNull(constraints, "constraints must not be null"));
        if (constraints.size() > 20) {
            throw new IllegalArgumentException("campaign must define at most 20 constraints");
        }
        for (String constraint : constraints) {
            if (constraint == null || constraint.isBlank()) {
                throw new IllegalArgumentException("campaign constraints must not be blank");
            }
        }
    }
}
