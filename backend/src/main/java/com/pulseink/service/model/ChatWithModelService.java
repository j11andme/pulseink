package com.pulseink.service.model;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamHandle;
import com.pulseink.service.model.ChatWithModelUseCase.InvalidModelInputException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class ChatWithModelService implements ChatWithModelUseCase {

    private static final int MAX_MESSAGE_LENGTH = 8_000;
    private static final String SYSTEM_PROMPT =
            "You are PulseInk, a content planning assistant. "
                    + "Return only the answer for the user.";

    private final AgentModelPort modelPort;

    public ChatWithModelService(AgentModelPort modelPort) {
        this.modelPort = Objects.requireNonNull(modelPort);
    }

    @Override
    public ModelStreamHandle chat(
            ChatCommand command,
            Consumer<ModelStreamEvent> eventConsumer) {
        Objects.requireNonNull(eventConsumer);
        if (command == null || command.message() == null || command.message().isBlank()) {
            throw new InvalidModelInputException("message must not be blank");
        }

        var normalizedMessage = command.message().trim();
        if (normalizedMessage.length() > MAX_MESSAGE_LENGTH) {
            throw new InvalidModelInputException(
                    "message must contain at most 8000 characters");
        }

        var request = new ModelRequest(
                UUID.randomUUID().toString(),
                SYSTEM_PROMPT,
                normalizedMessage,
                command.temperature(),
                command.maxTokens());
        return modelPort.stream(request, eventConsumer);
    }
}
