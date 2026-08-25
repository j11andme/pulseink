package com.pulseink.config.properties;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulseink.evaluation")
public record EvaluationProperties(
        Path root,
        Path reportRoot,
        Duration judgeTimeout,
        int maxOutputTokensPerCall,
        Duration completionTimeout) {

    public EvaluationProperties {
        root = root == null ? Path.of("..", "evals") : root;
        reportRoot = reportRoot == null ? root.resolve("reports") : reportRoot;
        judgeTimeout = judgeTimeout == null ? Duration.ofSeconds(180) : judgeTimeout;
        maxOutputTokensPerCall = maxOutputTokensPerCall == 0
                ? 8_192 : maxOutputTokensPerCall;
        completionTimeout = completionTimeout == null
                ? Duration.ofSeconds(180) : completionTimeout;
        if (judgeTimeout.isZero() || judgeTimeout.isNegative()) {
            throw new IllegalArgumentException("evaluation judge timeout must be positive");
        }
        if (maxOutputTokensPerCall < 1) {
            throw new IllegalArgumentException(
                    "evaluation max output tokens must be positive");
        }
        if (completionTimeout.isZero() || completionTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "evaluation completion timeout must be positive");
        }
    }
}
