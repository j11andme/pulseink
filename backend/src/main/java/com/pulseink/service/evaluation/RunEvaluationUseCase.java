package com.pulseink.service.evaluation;

import java.util.Optional;

public interface RunEvaluationUseCase {
    EvaluationReport run(EvaluationRequest request);
    EvaluationReport runCustom(CustomEvaluationRequest request);
    Optional<EvaluationReport> latest();
}
