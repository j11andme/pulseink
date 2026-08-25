package com.pulseink.service.evaluation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Supplies the currently executing frozen case to isolated evaluation tools. */
public final class EvaluationScenarioContext {

    private final AtomicReference<EvaluationCase> current = new AtomicReference<>();

    public void activate(EvaluationCase testCase) {
        if (!current.compareAndSet(null, Objects.requireNonNull(testCase))) {
            throw new IllegalStateException("evaluation scenario already active");
        }
    }

    public EvaluationCase current() {
        var value = current.get();
        if (value == null) throw new IllegalStateException("no evaluation scenario active");
        return value;
    }

    public void clear() {
        current.set(null);
    }
}
