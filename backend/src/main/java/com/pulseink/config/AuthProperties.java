package com.pulseink.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulseink.auth")
public record AuthProperties(
        String jwtSecret,
        Duration tokenTtl,
        String demoPassword) {}
