package com.pulseink.service.evaluation;

import com.pulseink.domain.campaign.CampaignChannel;
import java.util.List;

/** Auditable user input embedded in a CUSTOM report, separate from the fixed dataset. */
public record CustomEvaluationCase(
        String task,
        String expectedResult,
        String audience,
        CampaignChannel channel,
        List<String> constraints) {

    public CustomEvaluationCase {
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }
}
