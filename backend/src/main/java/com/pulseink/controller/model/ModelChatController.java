package com.pulseink.controller.model;

import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamEvent.Completed;
import com.pulseink.agent.model.ModelStreamEvent.ContentDelta;
import com.pulseink.agent.model.ModelStreamEvent.Failed;
import com.pulseink.agent.model.ModelStreamEvent.Started;
import com.pulseink.agent.model.ModelStreamEvent.Usage;
import com.pulseink.agent.model.ModelStreamHandle;
import com.pulseink.service.model.ChatWithModelUseCase;
import com.pulseink.service.model.ChatWithModelUseCase.ChatCommand;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/model")
public class ModelChatController {

    private static final long STREAM_TIMEOUT_MILLIS = 180_000L;

    private final ChatWithModelUseCase chatWithModelUseCase;

    public ModelChatController(ChatWithModelUseCase chatWithModelUseCase) {
        this.chatWithModelUseCase = Objects.requireNonNull(chatWithModelUseCase);
    }

    @PostMapping(
            path = "/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        var emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        var handle = new AttachedModelStreamHandle();
        emitter.onTimeout(() -> {
            handle.cancel();
            emitter.complete();
        });
        emitter.onCompletion(handle::cancel);
        emitter.onError(ignored -> handle.cancel());

        var upstream = chatWithModelUseCase.chat(
                new ChatCommand(
                        request.message(),
                        request.temperature(),
                        request.maxTokens()),
                event -> forward(event, emitter, handle));
        handle.attach(upstream);
        return emitter;
    }

    private void forward(
            ModelStreamEvent event,
            SseEmitter emitter,
            ModelStreamHandle handle) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName(event))
                    .data(event));
            if (event instanceof Completed || event instanceof Failed) {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException exception) {
            handle.cancel();
            emitter.completeWithError(exception);
        }
    }

    private String eventName(ModelStreamEvent event) {
        return switch (event) {
            case Started ignored -> "started";
            case ContentDelta ignored -> "content_delta";
            case Completed ignored -> "completed";
            case Failed ignored -> "error";
            case Usage ignored -> "usage";
        };
    }

    public record ChatRequest(
            String message,
            Double temperature,
            Integer maxTokens) {}

    private static final class AttachedModelStreamHandle
            implements ModelStreamHandle {

        private final AtomicReference<ModelStreamHandle> delegate =
                new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        void attach(ModelStreamHandle handle) {
            Objects.requireNonNull(handle);
            if (!delegate.compareAndSet(null, handle)) {
                throw new IllegalStateException("model stream handle already attached");
            }
            if (cancelled.get()) {
                handle.cancel();
            }
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            var current = delegate.get();
            if (current != null) {
                current.cancel();
            }
        }
    }
}
