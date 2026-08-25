package com.pulseink.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.pulseink.service.knowledge.KnowledgeIngestionCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

class KnowledgeRecoveryStartupTest {

    @Test
    void applicationReadyTriggersBoundedRecovery() {
        var coordinator = mock(KnowledgeIngestionCoordinator.class);
        var listener = new KnowledgeRecoveryStartup(coordinator);

        listener.onApplicationEvent(mock(ApplicationReadyEvent.class));

        verify(coordinator).recover();
    }
}
