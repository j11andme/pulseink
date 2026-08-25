package com.pulseink.repository.run;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.campaign.RunState;
import com.pulseink.domain.execution.ExecutionMode;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.service.campaign.RunRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisRunRepository implements RunRepository {

    private final CampaignRunMapper mapper;

    public MybatisRunRepository(CampaignRunMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public CampaignRun insert(CampaignRun run) {
        var entity = toEntity(run);
        mapper.insert(entity);
        var persisted = mapper.selectById(entity.getId());
        if (persisted == null) {
            throw new IllegalStateException(
                    "run insert did not produce a readable row for id " + entity.getId());
        }
        return toDomain(persisted);
    }

    @Override
    public Optional<CampaignRun> findById(long runId) {
        return Optional.ofNullable(mapper.selectById(runId))
                .map(MybatisRunRepository::toDomain);
    }

    @Override
    public List<CampaignRun> findByCampaignId(long campaignId) {
        var query = new QueryWrapper<CampaignRunEntity>()
                .eq("campaign_id", campaignId)
                .orderByDesc("created_at")
                .orderByDesc("id");
        return mapper.selectList(query).stream()
                .map(MybatisRunRepository::toDomain)
                .toList();
    }

    @Override
    public void update(CampaignRun run) {
        var entity = toEntity(run);
        int affected = mapper.updateStateCas(
                run.id(),
                run.state().name(),
                run.failureReason(),
                run.startedAt(),
                run.completedAt(),
                run.version());
        if (affected != 1) {
            throw new IllegalStateException(
                    "stale run update for id " + run.id()
                            + ": expected version " + run.version());
        }
    }

    private static CampaignRunEntity toEntity(CampaignRun run) {
        var entity = new CampaignRunEntity();
        entity.setCampaignId(run.campaignId());
        entity.setRequestedPolicy(run.requestedPolicy().name());
        if (run.selectedMode() != null) {
            entity.setSelectedMode(run.selectedMode().name());
        }
        entity.setSelectorPolicyVersion(run.selectorPolicyVersion());
        entity.setSelectionReasonJson(List.copyOf(run.selectionReasonCodes()));
        entity.setSelectionFeatureJson(Map.copyOf(run.selectionFeatureSnapshot()));
        entity.setEstimatedTokenBudget(run.estimatedTokenBudget());
        entity.setState(run.state().name());
        entity.setFailureReason(run.failureReason());
        entity.setStartedAt(run.startedAt());
        entity.setCompletedAt(run.completedAt());
        return entity;
    }

    private static CampaignRun toDomain(CampaignRunEntity entity) {
        return CampaignRun.materialize(
                entity.getId(),
                entity.getCampaignId(),
                toPolicy(entity.getRequestedPolicy()),
                toState(entity.getState()),
                entity.getSelectedMode() == null ? null : toMode(entity.getSelectedMode()),
                entity.getSelectorPolicyVersion(),
                entity.getSelectionReasonJson(),
                entity.getSelectionFeatureJson(),
                entity.getEstimatedTokenBudget() == null ? 0L : entity.getEstimatedTokenBudget(),
                entity.getFailureReason(),
                entity.getVersion() == null ? 0L : entity.getVersion(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static ExecutionPolicy toPolicy(String value) {
        try {
            return ExecutionPolicy.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "run stored an unknown policy value: " + value, ex);
        }
    }

    private static ExecutionMode toMode(String value) {
        try {
            return ExecutionMode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "run stored an unknown mode value: " + value, ex);
        }
    }

    private static RunState toState(String value) {
        try {
            return RunState.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "run stored an unknown state value: " + value, ex);
        }
    }
}
