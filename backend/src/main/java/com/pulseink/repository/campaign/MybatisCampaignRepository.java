package com.pulseink.repository.campaign;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pulseink.domain.campaign.Campaign;
import com.pulseink.domain.campaign.CampaignBrief;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.campaign.CampaignStatus;
import com.pulseink.service.campaign.CampaignRepository;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignPage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisCampaignRepository implements CampaignRepository {

    private final CampaignMapper mapper;

    public MybatisCampaignRepository(CampaignMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Campaign insert(Campaign draft) {
        var entity = toEntity(draft);
        mapper.insert(entity);
        var persisted = mapper.selectById(entity.getId());
        if (persisted == null) {
            throw new IllegalStateException(
                    "campaign insert did not produce a readable row for id " + entity.getId());
        }
        return toDomain(persisted);
    }

    @Override
    public CampaignPage findPage(int page, int size) {
        var total = mapper.selectCount(Wrappers.<CampaignEntity>lambdaQuery());
        long offset = (long) page * size;
        var entities = mapper.selectList(Wrappers.<CampaignEntity>lambdaQuery()
                .orderByDesc(CampaignEntity::getCreatedAt)
                .orderByDesc(CampaignEntity::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
        var items = entities.stream().map(MybatisCampaignRepository::toDomain).toList();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
        return new CampaignPage(items, page, size, total, totalPages);
    }

    @Override
    public Optional<Campaign> findById(long campaignId) {
        var entity = mapper.selectById(campaignId);
        return Optional.ofNullable(entity).map(MybatisCampaignRepository::toDomain);
    }

    private static CampaignEntity toEntity(Campaign draft) {
        var entity = new CampaignEntity();
        entity.setName(draft.name());
        entity.setObjective(draft.brief().objective());
        entity.setAudience(draft.brief().audience());
        entity.setChannelsJson(draft.brief().channels().stream()
                .map(CampaignChannel::name)
                .collect(Collectors.toList()));
        entity.setConstraintsJson(List.copyOf(draft.brief().constraints()));
        entity.setStatus(draft.status().name());
        entity.setCreatedBy(draft.createdBy());
        entity.setVersion(draft.version());
        return entity;
    }

    private static Campaign toDomain(CampaignEntity entity) {
        var channels = entity.getChannelsJson().stream()
                .map(MybatisCampaignRepository::toChannel)
                .toList();
        var constraints = List.copyOf(entity.getConstraintsJson());
        var brief = new CampaignBrief(
                entity.getObjective(),
                entity.getAudience(),
                channels,
                constraints);
        var status = toStatus(entity.getStatus());
        return new Campaign(
                entity.getId(),
                entity.getName(),
                brief,
                status,
                entity.getCreatedBy(),
                entity.getVersion() == null ? 0L : entity.getVersion(),
                Optional.ofNullable(entity.getCreatedAt()),
                Optional.ofNullable(entity.getUpdatedAt()));
    }

    private static CampaignChannel toChannel(String value) {
        try {
            return CampaignChannel.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "campaign stored an unknown channel value: " + value, ex);
        }
    }

    private static CampaignStatus toStatus(String value) {
        try {
            return CampaignStatus.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "campaign stored an unknown status value: " + value, ex);
        }
    }
}
