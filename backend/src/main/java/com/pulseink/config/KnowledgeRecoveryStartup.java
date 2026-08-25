package com.pulseink.config;

import com.pulseink.service.knowledge.KnowledgeIngestionCoordinator;
import java.util.Objects;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/** Starts bounded recovery only after the application is ready to accept work. */
public final class KnowledgeRecoveryStartup implements ApplicationListener<ApplicationReadyEvent> {

    private final KnowledgeIngestionCoordinator coordinator;

    public KnowledgeRecoveryStartup(KnowledgeIngestionCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        coordinator.recover();
    }
}
