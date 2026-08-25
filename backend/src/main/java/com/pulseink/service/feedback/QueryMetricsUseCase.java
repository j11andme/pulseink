package com.pulseink.service.feedback;

import com.pulseink.domain.feedback.ContentMetricDaily;
import java.util.List;

public interface QueryMetricsUseCase {

    List<ContentMetricDaily> findByRunId(long runId);
}
