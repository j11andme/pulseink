package com.pulseink.service.memory;

import com.pulseink.config.properties.MemoryProperties;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.CampaignInsight;
import java.util.List;
import java.util.Objects;

/**
 * Insight queries. The REST path maps Elasticsearch unavailability to a stable 503 instead of
 * pretending empty results.
 */
public final class QueryInsightService implements QueryInsightUseCase {

    private final CampaignInsightRepository repository;
    private final InsightSearchStore store;
    private final MemoryProperties properties;

    public QueryInsightService(CampaignInsightRepository repository,
                               InsightSearchStore store,
                               MemoryProperties properties) {
        this.repository = Objects.requireNonNull(repository);
        this.store = Objects.requireNonNull(store);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public List<CampaignInsight> listByCampaign(long campaignId) {
        if (campaignId <= 0) {
            throw new InsightException(InsightErrorCode.VALIDATION_ERROR,
                    "campaign id must be positive");
        }
        return repository.findByCampaign(campaignId);
    }

    @Override
    public List<ApprovedInsightHit> searchApproved(String query,
                                                   CampaignChannel channel,
                                                   int topK) {
        if (query == null || query.isBlank()) {
            throw new InsightException(InsightErrorCode.VALIDATION_ERROR,
                    "query must not be blank");
        }
        int effectiveTopK = topK <= 0 ? properties.approvedTopK()
                : Math.min(topK, properties.maxSearchTopK());
        try {
            return store.search(query.strip(), channel, effectiveTopK);
        } catch (InsightException searchFailure) {
            throw searchFailure;
        } catch (RuntimeException failure) {
            throw new InsightException(InsightErrorCode.INSIGHT_SEARCH_UNAVAILABLE,
                    "insight search is unavailable", failure);
        }
    }
}
