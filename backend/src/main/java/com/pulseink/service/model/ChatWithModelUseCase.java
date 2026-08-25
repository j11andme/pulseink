package com.pulseink.service.model;

import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamHandle;
import java.util.function.Consumer;

public interface ChatWithModelUseCase {

    ModelStreamHandle chat(
            ChatCommand command,
            Consumer<ModelStreamEvent> eventConsumer);

    record ChatCommand(
            String message,
            Double temperature,
            Integer maxTokens) {}

    final class InvalidModelInputException extends IllegalArgumentException {
        public InvalidModelInputException(String message) {
            super(message);
        }
    }
}
