package com.pulseink.sandbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 {@link ObjectMapper} for JSON column persistence in the sandbox. Spring Boot 4.1
 * auto-configures Jackson 3 ({@code tools.jackson}) for web serialization, so the Jackson 2
 * mapper used by the JDBC repositories must be declared explicitly, mirroring the backend.
 */
@Configuration
public class JsonConfiguration {

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
