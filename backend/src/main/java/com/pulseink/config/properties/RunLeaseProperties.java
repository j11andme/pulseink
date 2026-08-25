package com.pulseink.config.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-instance run lease configuration. The owner id defaults to appName + host/pid + UUID at
 * wiring time so two JVM instances can never share an owner.
 */
@ConfigurationProperties("pulseink.run-lease")
public record RunLeaseProperties(
        Boolean enabled,
        String ownerId,
        Duration ttl,
        Duration renewInterval) {

    public RunLeaseProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            ttl = Duration.ofSeconds(30);
        }
        if (renewInterval == null || renewInterval.isZero() || renewInterval.isNegative()) {
            renewInterval = Duration.ofSeconds(10);
        }
        if (renewInterval.compareTo(ttl.dividedBy(2)) >= 0) {
            throw new IllegalStateException(
                    "pulseink.run-lease.renew-interval must be smaller than half of the ttl");
        }
    }
}
