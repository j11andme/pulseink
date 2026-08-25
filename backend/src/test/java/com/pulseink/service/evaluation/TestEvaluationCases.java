package com.pulseink.service.evaluation;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.execution.TaskProperties;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

final class TestEvaluationCases {

    private TestEvaluationCases() {}

    static List<EvaluationCase> sixSmokeCases() {
        return IntStream.rangeClosed(1, 6)
                .mapToObj(index -> new EvaluationCase(
                        "smoke-0" + index, "NORMAL", true,
                        new TaskProperties(0.7, 2, 2, 2, 0.5, 0.7, 2, 20_000),
                        new EvaluationCase.CampaignInput("Campaign " + index, "goal", "audience",
                                List.of(CampaignChannel.BLOG), List.of()),
                        "fixtures/knowledge/brand-a-v1.json",
                        "fixtures/search/campaign-a.json",
                        List.of("approval_required"), List.of("chunk-a"), Set.of(),
                        "WAITING_APPROVAL", "rubrics/content-v1.json", List.of()))
                .toList();
    }
}
