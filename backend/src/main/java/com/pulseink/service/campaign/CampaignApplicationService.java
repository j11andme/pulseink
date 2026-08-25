package com.pulseink.service.campaign;

import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignBrief;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.service.campaign.CreateCampaignUseCase.CreateCampaignCommand;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignNotFoundException;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignPage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

public class CampaignApplicationService
        implements CreateCampaignUseCase, QueryCampaignUseCase {

    static final int MAX_NAME_LENGTH = 128;
    static final int MAX_OBJECTIVE_LENGTH = 4_000;
    static final int MAX_AUDIENCE_LENGTH = 2_000;
    static final int MAX_CHANNEL_COUNT = 3;
    static final int MAX_CONSTRAINT_COUNT = 20;
    static final int MAX_CONSTRAINT_LENGTH = 500;
    static final int MIN_PAGE_SIZE = 1;
    static final int MAX_PAGE_SIZE = 100;

    private final CampaignRepository campaignRepository;

    public CampaignApplicationService(CampaignRepository campaignRepository) {
        this.campaignRepository = Objects.requireNonNull(campaignRepository);
    }

    @Override
    @Transactional
    public Campaign create(CreateCampaignCommand command, long actorUserId) {
        if (actorUserId <= 0) {
            throw new IllegalArgumentException("actor user id must be positive");
        }

        var trimmedName = requireTrimmed(command != null ? command.name() : null, "campaign name");
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("campaign name must contain at most 128 characters");
        }

        var objective = requireTrimmed(command.objective(), "campaign objective");
        if (objective.length() > MAX_OBJECTIVE_LENGTH) {
            throw new IllegalArgumentException("campaign objective must contain at most 4000 characters");
        }

        var audience = requireTrimmed(command.audience(), "campaign audience");
        if (audience.length() > MAX_AUDIENCE_LENGTH) {
            throw new IllegalArgumentException("campaign audience must contain at most 2000 characters");
        }

        var channels = requireChannels(command.channels());
        var constraints = requireConstraints(command.constraints());

        var brief = new CampaignBrief(objective, audience, channels, constraints);
        var draft = Campaign.unpersistedDraft(trimmedName, brief, actorUserId);
        return campaignRepository.insert(draft);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignPage list(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        return campaignRepository.findPage(page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public Campaign get(long campaignId) {
        if (campaignId <= 0) {
            throw new IllegalArgumentException("campaign id must be positive");
        }
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));
    }

    private static String requireTrimmed(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static List<CampaignChannel> requireChannels(List<CampaignChannel> channels) {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("campaign must target at least one channel");
        }
        var distinct = new ArrayList<CampaignChannel>();
        Set<CampaignChannel> seen = new HashSet<>();
        for (CampaignChannel channel : channels) {
            if (channel == null) {
                throw new IllegalArgumentException("campaign channel must not be null");
            }
            if (!seen.add(channel)) {
                throw new IllegalArgumentException("campaign channels must not contain duplicates");
            }
            distinct.add(channel);
        }
        if (distinct.size() > MAX_CHANNEL_COUNT) {
            throw new IllegalArgumentException("campaign must target at most 3 channels");
        }
        return List.copyOf(distinct);
    }

    private static List<String> requireConstraints(List<String> constraints) {
        if (constraints == null) {
            throw new IllegalArgumentException("campaign constraints must not be null");
        }
        if (constraints.size() > MAX_CONSTRAINT_COUNT) {
            throw new IllegalArgumentException("campaign must define at most 20 constraints");
        }
        var normalized = new ArrayList<String>(constraints.size());
        for (String constraint : constraints) {
            if (constraint == null || constraint.isBlank()) {
                throw new IllegalArgumentException("campaign constraints must not be blank");
            }
            var trimmed = constraint.trim();
            if (trimmed.length() > MAX_CONSTRAINT_LENGTH) {
                throw new IllegalArgumentException("campaign constraints must contain at most 500 characters");
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }
}
