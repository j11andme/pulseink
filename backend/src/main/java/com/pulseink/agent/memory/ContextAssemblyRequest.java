package com.pulseink.agent.memory;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.domain.campaign.CampaignChannel;
import java.util.List;

/**
 * One context assembly request for a role profile. The assembler must produce identical output
 * for identical input.
 */
public record ContextAssemblyRequest(
        long runId,
        AgentProfile profile,
        String campaignBrief,
        String currentObjective,
        List<AgentArtifact> currentArtifacts,
        List<CampaignChannel> campaignChannels,
        int maxCodePoints) {

    public ContextAssemblyRequest {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId must be positive");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        currentArtifacts = currentArtifacts == null ? List.of() : List.copyOf(currentArtifacts);
        campaignChannels = campaignChannels == null
                ? List.of()
                : List.copyOf(campaignChannels);
    }
}
