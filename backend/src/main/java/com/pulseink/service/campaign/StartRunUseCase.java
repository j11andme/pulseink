package com.pulseink.service.campaign;

import com.pulseink.domain.campaign.CampaignRun;
import com.pulseink.domain.execution.ExecutionPolicy;
import com.pulseink.domain.execution.TaskProperties;

public interface StartRunUseCase {

    CampaignRun start(StartRunCommand command);

    record StartRunCommand(
            long campaignId,
            ExecutionPolicy requestedPolicy,
            TaskProperties taskProperties) {
    }
}
