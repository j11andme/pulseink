package com.pulseink.service.content;

import com.pulseink.domain.content.ContentItem;
import com.pulseink.domain.content.ReviewReport;
import java.util.List;

public interface QueryContentUseCase {
    List<ContentItem> findByRunId(long runId);
    ContentItem get(long contentId);
    List<ReviewReport> findReviewsByRunId(long runId);
}
