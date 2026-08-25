package com.pulseink.service.evaluation;

import java.nio.file.Path;

@FunctionalInterface
public interface EvaluationReportWriter {
    Path write(EvaluationReport report);
}
