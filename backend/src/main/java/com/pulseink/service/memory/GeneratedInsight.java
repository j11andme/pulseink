package com.pulseink.service.memory;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightEvidenceRef;
import com.pulseink.domain.memory.InsightScopeType;
import java.util.List;

/**
 * Strictly parsed single insight candidate from the model protocol. Field-level business
 * invariants are enforced by the parser against the source snapshot; the domain
 * {@code CampaignInsight} re-validates them again on persistence.
 */
public record GeneratedInsight(
        int schemaVersion,
        InsightCategory category,
        String title,
        String insightText,
        InsightScopeType scopeType,
        String scopeValue,
        List<CampaignChannel> applicableChannels,
        List<InsightEvidenceRef> evidenceRefs,
        double confidence,
        List<String> limitations) {

    public GeneratedInsight {
        applicableChannels = List.copyOf(applicableChannels);
        evidenceRefs = List.copyOf(evidenceRefs);
        limitations = List.copyOf(limitations);
    }
}
