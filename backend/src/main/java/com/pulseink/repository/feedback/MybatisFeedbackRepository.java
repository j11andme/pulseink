package com.pulseink.repository.feedback;

import com.pulseink.domain.feedback.ContentMetricDaily;
import com.pulseink.domain.feedback.FeedbackEvent;
import com.pulseink.service.feedback.FeedbackRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisFeedbackRepository implements FeedbackRepository {

    private final FeedbackMapper mapper;

    public MybatisFeedbackRepository(FeedbackMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    @Transactional
    public boolean ingest(FeedbackEvent event, String sourceTopic,
                          int sourcePartition, long sourceOffset) {
        try {
            mapper.insertInbox(event.eventId().toString(), event.publicationId(),
                    event.schemaVersion(), sourceTopic, sourcePartition, sourceOffset);
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
        mapper.upsertMetric(event.publicationId(), event.metricDate(),
                event.views(), event.clicks(), event.likes());
        return true;
    }

    @Override
    public PublicationRef findByPublicationId(long publicationId) {
        var row = mapper.selectPublicationRow(publicationId);
        if (row == null) {
            throw new IllegalArgumentException(
                    "publication " + publicationId + " was not found");
        }
        return new PublicationRef(row.getId(), row.getRunId());
    }

    @Override
    public List<ContentMetricDaily> findByRunId(long runId) {
        return mapper.findMetricsByRunId(runId).stream()
                .map(entity -> new ContentMetricDaily(
                        entity.getPublicationId(),
                        entity.getMetricDate(),
                        entity.getViews(),
                        entity.getClicks(),
                        entity.getLikes()))
                .toList();
    }
}
