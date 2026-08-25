package com.pulseink.service.evaluation;

import java.util.List;

public interface EvaluationCaseCatalog {

    List<EvaluationCase> all();

    default List<EvaluationCase> smokeCases() {
        return all().stream().filter(EvaluationCase::smoke).toList();
    }
}
