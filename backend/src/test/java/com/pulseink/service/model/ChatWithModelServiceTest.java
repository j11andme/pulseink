package com.pulseink.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamEvent.Completed;
import com.pulseink.agent.model.ModelStreamEvent.ContentDelta;
import com.pulseink.agent.model.ModelStreamEvent.Started;
import com.pulseink.service.model.ChatWithModelUseCase.ChatCommand;
import com.pulseink.service.model.ChatWithModelUseCase.InvalidModelInputException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChatWithModelServiceTest {

    @Test
    void streamsNormalizedModelEventsInProviderOrder() {
        var capturedRequest = new AtomicReference<ModelRequest>();
        AgentModelPort model = (request, eventConsumer) -> {
            capturedRequest.set(request);
            eventConsumer.accept(new Started(request.requestId(), "fake", "pulseink-fake"));
            eventConsumer.accept(new ContentDelta(request.requestId(), "Pulse"));
            eventConsumer.accept(new ContentDelta(request.requestId(), "Ink"));
            eventConsumer.accept(new Completed(request.requestId(), "STOP"));
            return () -> {};
        };
        var service = new ChatWithModelService(model);
        var events = new ArrayList<ModelStreamEvent>();

        service.chat(new ChatCommand("  介绍 PulseInk  ", 0.3, 512), events::add);

        assertThat(events)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly("Started", "ContentDelta", "ContentDelta", "Completed");
        assertThat(events.stream()
                        .filter(ContentDelta.class::isInstance)
                        .map(ContentDelta.class::cast)
                        .map(ContentDelta::content))
                .containsExactly("Pulse", "Ink");
        assertThat(capturedRequest.get().requestId()).isNotBlank();
        assertThat(capturedRequest.get().systemPrompt())
                .isEqualTo(
                        "You are PulseInk, a content planning assistant. "
                                + "Return only the answer for the user.");
        assertThat(capturedRequest.get().userPrompt()).isEqualTo("介绍 PulseInk");
        assertThat(capturedRequest.get().temperature()).isEqualTo(0.3);
        assertThat(capturedRequest.get().maxTokens()).isEqualTo(512);
        assertThat(events).allSatisfy(event ->
                assertThat(event.requestId()).isEqualTo(capturedRequest.get().requestId()));
    }

    @Test
    void rejectsBlankMessagesBeforeCallingTheModel() {
        AgentModelPort model = (request, eventConsumer) -> {
            throw new AssertionError("model must not be called");
        };
        var service = new ChatWithModelService(model);

        assertThatThrownBy(() ->
                        service.chat(new ChatCommand("   ", null, null), event -> {}))
                .isInstanceOf(InvalidModelInputException.class)
                .hasMessage("message must not be blank");
    }

    @Test
    void rejectsMessagesLongerThanEightThousandCharacters() {
        AgentModelPort model = (request, eventConsumer) -> {
            throw new AssertionError("model must not be called");
        };
        var service = new ChatWithModelService(model);

        assertThatThrownBy(() ->
                        service.chat(new ChatCommand("x".repeat(8001), null, null), event -> {}))
                .isInstanceOf(InvalidModelInputException.class)
                .hasMessage("message must contain at most 8000 characters");
    }
}
