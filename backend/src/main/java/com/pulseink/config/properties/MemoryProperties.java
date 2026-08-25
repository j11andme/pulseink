package com.pulseink.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulseink.memory")
public record MemoryProperties(
        String indexAlias,
        Integer approvedTopK,
        Integer maxSearchTopK,
        Integer contextMaxCodePoints,
        Duration runCacheTtl,
        Boolean indexWorkerEnabled,
        Duration indexWorkerDelay,
        Integer indexMaxAttempts) {

    public MemoryProperties {
        if (indexAlias == null || indexAlias.isBlank()) {
            indexAlias = "pulseink-memory-insight-active";
        }
        if (approvedTopK == null || approvedTopK <= 0) {
            approvedTopK = 3;
        }
        if (maxSearchTopK == null || maxSearchTopK <= 0 || maxSearchTopK > 10) {
            maxSearchTopK = 10;
        }
        if (contextMaxCodePoints == null || contextMaxCodePoints <= 0) {
            contextMaxCodePoints = 12_000;
        }
        if (runCacheTtl == null) {
            runCacheTtl = Duration.ofMinutes(30);
        }
        if (indexWorkerEnabled == null) {
            indexWorkerEnabled = true;
        }
        if (indexWorkerDelay == null) {
            indexWorkerDelay = Duration.ofSeconds(2);
        }
        if (indexMaxAttempts == null || indexMaxAttempts <= 0) {
            indexMaxAttempts = 3;
        }
    }
}
