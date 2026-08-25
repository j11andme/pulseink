package com.pulseink.controller.feedback;

import com.pulseink.domain.feedback.ContentMetricDaily;
import com.pulseink.service.feedback.QueryMetricsUseCase;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metrics REST contract: GET /api/runs/{runId}/metrics returns the actual aggregated daily
 * counters ordered by publicationId and metricDate. No CTR uplift claims are computed here.
 */
@RestController
@RequestMapping("/api")
public class FeedbackController {

    private final QueryMetricsUseCase metrics;

    public FeedbackController(QueryMetricsUseCase metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/runs/{runId}/metrics")
    public List<MetricResponse> metrics(@PathVariable long runId) {
        return metrics.findByRunId(runId).stream()
                .map(FeedbackController::toResponse).toList();
    }

    private static MetricResponse toResponse(ContentMetricDaily metric) {
        return new MetricResponse(metric.publicationId(), metric.metricDate(),
                metric.views(), metric.clicks(), metric.likes());
    }

    public record MetricResponse(
            long publicationId,
            LocalDate metricDate,
            long views,
            long clicks,
            long likes) {
    }
}
