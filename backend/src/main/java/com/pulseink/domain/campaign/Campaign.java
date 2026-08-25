package com.pulseink.domain.campaign;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record Campaign(
        long id,
        String name,
        CampaignBrief brief,
        CampaignStatus status,
        long createdBy,
        long version,
        Optional<Instant> createdAt,
        Optional<Instant> updatedAt) {

    public Campaign {
        Objects.requireNonNull(name, "campaign name must not be null");
        Objects.requireNonNull(brief, "campaign brief must not be null");
        Objects.requireNonNull(status, "campaign status must not be null");
        Objects.requireNonNull(createdAt, "campaign createdAt must not be null");
        Objects.requireNonNull(updatedAt, "campaign updatedAt must not be null");
    }

    public static Campaign unpersistedDraft(
            String name,
            CampaignBrief brief,
            long createdBy) {
        return new Campaign(
                0L,
                name,
                brief,
                CampaignStatus.DRAFT,
                createdBy,
                0L,
                Optional.empty(),
                Optional.empty());
    }
}
