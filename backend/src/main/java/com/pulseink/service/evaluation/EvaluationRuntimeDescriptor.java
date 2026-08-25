package com.pulseink.service.evaluation;

public record EvaluationRuntimeDescriptor(
        String provider,
        String model,
        boolean simulated) {

    public EvaluationRuntimeDescriptor {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
    }

    public static EvaluationRuntimeDescriptor unknown() {
        return new EvaluationRuntimeDescriptor("unknown", "unknown", false);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
