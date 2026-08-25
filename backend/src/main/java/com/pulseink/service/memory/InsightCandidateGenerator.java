package com.pulseink.service.memory;

/**
 * One-shot model capability that turns an eligible campaign fact snapshot into one strictly
 * parsed insight candidate. Not an agent: no Plan, no ReAct loop, no tools.
 */
public interface InsightCandidateGenerator {

    GeneratedInsight generate(InsightSourceSnapshot source);
}
