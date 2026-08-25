package com.pulseink.service.integration;

import java.util.List;

/**
 * Read-only "configuration/capability status" surface for the Integrations page. It reports
 * static wiring state; it never probes external services and never carries secrets or internal
 * URIs.
 */
public interface QueryIntegrationUseCase {

    IntegrationStatus status();

    record IntegrationStatus(
            List<Integration> integrations,
            List<Tool> tools) {

        public IntegrationStatus {
            integrations = integrations == null ? List.of() : List.copyOf(integrations);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record Integration(
            String id,
            String displayName,
            String category,
            String status,
            String summary,
            List<String> capabilities) {

        public Integration {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }
    }

    record Tool(
            String qualifiedName,
            String risk,
            String description) {
    }
}
