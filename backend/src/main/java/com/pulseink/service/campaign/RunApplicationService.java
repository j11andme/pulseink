package com.pulseink.service.campaign;

import com.pulseink.agent.selection.ExecutionModeSelector;
import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.service.campaign.QueryCampaignUseCase.CampaignNotFoundException;
import com.pulseink.service.campaign.QueryRunUseCase.RunNotFoundException;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

public class RunApplicationService implements StartRunUseCase, QueryRunUseCase {

    private final CampaignRepository campaignRepository;
    private final RunRepository runRepository;
    private final ExecutionModeSelector modeSelector;
    private final RunJournal runJournal;

    public RunApplicationService(
            CampaignRepository campaignRepository,
            RunRepository runRepository,
            ExecutionModeSelector modeSelector,
            RunJournal runJournal) {
        this.campaignRepository = Objects.requireNonNull(campaignRepository);
        this.runRepository = Objects.requireNonNull(runRepository);
        this.modeSelector = Objects.requireNonNull(modeSelector);
        this.runJournal = Objects.requireNonNull(runJournal);
    }

    @Override
    @Transactional
    public CampaignRun start(StartRunCommand command) {
        Objects.requireNonNull(command, "start run command must not be null");
        if (command.campaignId() <= 0) {
            throw new IllegalArgumentException("campaign id must be positive");
        }
        var requestedPolicy = Objects.requireNonNull(
                command.requestedPolicy(), "requested policy must not be null");
        var taskProperties = Objects.requireNonNull(
                command.taskProperties(), "task properties must not be null");

        var campaign = campaignRepository.findById(command.campaignId())
                .orElseThrow(() -> new CampaignNotFoundException(command.campaignId()));
        if (taskProperties.channelCount() != campaign.brief().channels().size()) {
            throw new IllegalArgumentException(
                    "task channel count must match the campaign brief channel count");
        }

        var run = CampaignRun.create(command.campaignId(), requestedPolicy);
        run.select(modeSelector.select(requestedPolicy, taskProperties));
        return runRepository.insert(run);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignRun executionDecision(long runId) {
        if (runId <= 0) {
            throw new IllegalArgumentException("run id must be positive");
        }
        return runRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CampaignRun> history(long campaignId) {
        if (campaignId <= 0) {
            throw new IllegalArgumentException("campaign id must be positive");
        }
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));
        return List.copyOf(runRepository.findByCampaignId(campaignId));
    }

    @Override
    @Transactional(readOnly = true)
    public RunTraceSnapshot trace(long runId) {
        if (runId <= 0) {
            throw new IllegalArgumentException("run id must be positive");
        }
        var run = runRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException(runId));
        var checkpoint = runJournal.latestCheckpoint(runId).orElse(null);
        var events = runJournal.findEventsAfter(runId, 0L);
        long lastEventSequence = events.isEmpty()
                ? 0L
                : events.get(events.size() - 1).sequence();
        return new RunTraceSnapshot(run, lastEventSequence, checkpoint, events);
    }
}
