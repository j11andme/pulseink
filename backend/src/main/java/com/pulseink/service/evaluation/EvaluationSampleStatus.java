package com.pulseink.service.evaluation;

/** Separates scored agent behaviour from evaluation/provider infrastructure failures. */
public enum EvaluationSampleStatus {
    SCORED,
    ERROR
}
