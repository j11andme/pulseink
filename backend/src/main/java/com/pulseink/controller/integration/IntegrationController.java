package com.pulseink.controller.integration;

import com.pulseink.service.integration.QueryIntegrationUseCase;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IntegrationController {

    private final QueryIntegrationUseCase queryIntegrationUseCase;

    public IntegrationController(QueryIntegrationUseCase queryIntegrationUseCase) {
        this.queryIntegrationUseCase = Objects.requireNonNull(queryIntegrationUseCase);
    }

    @GetMapping("/integrations")
    public QueryIntegrationUseCase.IntegrationStatus status() {
        return queryIntegrationUseCase.status();
    }
}
