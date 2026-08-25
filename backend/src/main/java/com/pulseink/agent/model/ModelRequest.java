package com.pulseink.agent.model;

public record ModelRequest(
        String requestId,
        String systemPrompt,
        String userPrompt,
        Double temperature,
        Integer maxTokens,
        OutputFormat outputFormat,
        java.time.Duration timeout) {

    public ModelRequest {
        outputFormat = outputFormat == null ? OutputFormat.TEXT : outputFormat;
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public ModelRequest(
            String requestId,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            Integer maxTokens) {
        this(requestId, systemPrompt, userPrompt, temperature, maxTokens,
                OutputFormat.TEXT, null);
    }

    public ModelRequest(
            String requestId,
            String systemPrompt,
            String userPrompt,
            Double temperature,
            Integer maxTokens,
            OutputFormat outputFormat) {
        this(requestId, systemPrompt, userPrompt, temperature, maxTokens,
                outputFormat, null);
    }

    public enum OutputFormat {
        TEXT,
        JSON_OBJECT
    }
}
