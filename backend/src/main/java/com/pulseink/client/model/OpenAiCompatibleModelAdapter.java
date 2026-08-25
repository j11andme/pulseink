package com.pulseink.client.model;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelFailureKind;
import com.pulseink.agent.model.ModelCallException;
import com.pulseink.agent.model.ModelCompletion;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamEvent.Completed;
import com.pulseink.agent.model.ModelStreamEvent.ContentDelta;
import com.pulseink.agent.model.ModelStreamEvent.Failed;
import com.pulseink.agent.model.ModelStreamEvent.Started;
import com.pulseink.agent.model.ModelStreamEvent.Usage;
import com.pulseink.agent.model.ModelStreamHandle;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.Disposable;

public final class OpenAiCompatibleModelAdapter implements AgentModelPort {

    private static final String DEFAULT_FINISH_REASON = "STOP";
    private static final String PROVIDER_ERROR_MESSAGE =
            "Model provider request failed";
    private static final String EMPTY_RESPONSE_CODE = "MODEL_EMPTY_RESPONSE";
    private static final String EMPTY_RESPONSE_MESSAGE =
            "Model provider completed without a visible answer";
    private static final String TRUNCATED_RESPONSE_MESSAGE =
            "Model output ended before a visible answer was produced; increase max tokens and retry";

    private final ChatModel synchronousChatModel;
    private final StreamingChatModel streamingChatModel;
    private final String provider;
    private final String model;

    public OpenAiCompatibleModelAdapter(
            StreamingChatModel chatModel,
            String provider,
            String model) {
        this(null, chatModel, provider, model);
    }

    public OpenAiCompatibleModelAdapter(
            ChatModel synchronousChatModel,
            StreamingChatModel streamingChatModel,
            String provider,
            String model) {
        this.synchronousChatModel = synchronousChatModel;
        this.streamingChatModel = Objects.requireNonNull(streamingChatModel);
        this.provider = requireText(provider, "provider");
        this.model = requireText(model, "model");
    }

    @Override
    public ModelStreamHandle stream(
            ModelRequest request,
            Consumer<ModelStreamEvent> eventConsumer) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(eventConsumer);

        eventConsumer.accept(new Started(request.requestId(), provider, model));
        var finishReason = new AtomicReference<>(DEFAULT_FINISH_REASON);
        var visibleContentEmitted = new AtomicBoolean();

        try {
            var subscription = streamingChatModel.stream(toPrompt(request))
                    .subscribe(
                            response ->
                                    emitResponse(
                                            request.requestId(),
                                            response,
                                            finishReason,
                                            visibleContentEmitted,
                                            eventConsumer),
                            error -> eventConsumer.accept(
                                    failureEvent(request.requestId(), error)),
                            () -> eventConsumer.accept(completionEvent(
                                    request.requestId(),
                                    finishReason.get(),
                                    visibleContentEmitted.get())));
            return subscription::dispose;
        } catch (RuntimeException error) {
            eventConsumer.accept(failureEvent(request.requestId(), error));
            return () -> {};
        }
    }

    private Prompt toPrompt(ModelRequest request) {
        var optionsBuilder = OpenAiChatOptions.builder().model(model);
        if (request.temperature() != null) {
            optionsBuilder.temperature(request.temperature());
        }
        if (request.maxTokens() != null) {
            optionsBuilder.maxTokens(request.maxTokens());
        }
        if (request.timeout() != null) {
            optionsBuilder.timeout(request.timeout());
        }
        if (request.outputFormat() == ModelRequest.OutputFormat.JSON_OBJECT) {
            optionsBuilder.responseFormat(
                    org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.builder()
                            .type(org.springframework.ai.openai.OpenAiChatModel
                                    .ResponseFormat.Type.JSON_OBJECT)
                            .build());
        }
        return new Prompt(
                List.of(
                        new SystemMessage(request.systemPrompt()),
                        new UserMessage(request.userPrompt())),
                optionsBuilder.build());
    }

    private void emitResponse(
            String requestId,
            ChatResponse response,
            AtomicReference<String> finishReason,
            AtomicBoolean visibleContentEmitted,
            Consumer<ModelStreamEvent> eventConsumer) {
        if (response == null || response.getResult() == null) {
            return;
        }

        var generation = response.getResult();
        var metadata = generation.getMetadata();
        if (metadata != null
                && metadata.getFinishReason() != null
                && !metadata.getFinishReason().isBlank()) {
            finishReason.set(metadata.getFinishReason());
        }

        var output = generation.getOutput();
        if (output != null && output.getText() != null && !output.getText().isBlank()) {
            eventConsumer.accept(new ContentDelta(requestId, output.getText()));
            visibleContentEmitted.set(true);
        }
        var responseMetadata = response.getMetadata();
        if (responseMetadata != null && responseMetadata.getUsage() != null) {
            var usage = responseMetadata.getUsage();
            Integer inputTokens = usage.getPromptTokens();
            Integer outputTokens = usage.getCompletionTokens();
            if (inputTokens != null && outputTokens != null
                    && inputTokens >= 0 && outputTokens >= 0
                    && (inputTokens > 0 || outputTokens > 0)) {
                eventConsumer.accept(new Usage(
                        requestId, inputTokens.longValue(), outputTokens.longValue()));
            }
        }
    }

    /**
     * Structured Agent decisions use the provider's non-streaming JSON response. This avoids
     * reconstructing one JSON document from transport chunks, while the Playground keeps its
     * streaming UX through {@link #stream(ModelRequest, Consumer)}.
     */
    @Override
    public ModelCompletion complete(ModelRequest request, Duration timeout) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(timeout);
        if (synchronousChatModel == null
                || request.outputFormat() != ModelRequest.OutputFormat.JSON_OBJECT) {
            return AgentModelPort.super.complete(request, timeout);
        }
        try {
            return toCompletion(request, synchronousChatModel.call(toPrompt(request)));
        } catch (ModelCallException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ModelCallException(
                    classify(failure), "model provider request failed");
        }
    }

    private ModelCompletion toCompletion(ModelRequest request, ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new ModelCallException(
                    ModelFailureKind.EMPTY_RESPONSE, EMPTY_RESPONSE_MESSAGE);
        }
        var result = response.getResult();
        String finishReason = DEFAULT_FINISH_REASON;
        if (result.getMetadata() != null
                && result.getMetadata().getFinishReason() != null
                && !result.getMetadata().getFinishReason().isBlank()) {
            finishReason = result.getMetadata().getFinishReason();
        }
        long inputTokens = 0;
        long outputTokens = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            inputTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            outputTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        }
        return new ModelCompletion(
                request.requestId(), provider, model,
                result.getOutput().getText(), inputTokens, outputTokens, finishReason);
    }

    private ModelStreamEvent completionEvent(
            String requestId,
            String finishReason,
            boolean visibleContentEmitted) {
        if (visibleContentEmitted) {
            return new Completed(requestId, finishReason);
        }
        var message = "LENGTH".equalsIgnoreCase(finishReason)
                ? TRUNCATED_RESPONSE_MESSAGE
                : EMPTY_RESPONSE_MESSAGE;
        return new Failed(requestId, EMPTY_RESPONSE_CODE, message);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Failed failureEvent(String requestId, Throwable error) {
        var kind = classify(error);
        String code = switch (kind) {
            case RATE_LIMIT -> "MODEL_RATE_LIMIT";
            case TIMEOUT -> "MODEL_TIMEOUT";
            case SERVER -> "MODEL_PROVIDER_ERROR";
            case AUTHENTICATION -> "MODEL_AUTHENTICATION_ERROR";
            case INVALID_REQUEST -> "MODEL_INVALID_REQUEST";
            case EMPTY_RESPONSE -> EMPTY_RESPONSE_CODE;
            case UNKNOWN -> "MODEL_UNKNOWN";
        };
        String message = switch (kind) {
            case RATE_LIMIT -> "Model provider rate limit exceeded";
            case TIMEOUT -> "Model provider request timed out";
            case AUTHENTICATION -> "Model provider authentication failed";
            case INVALID_REQUEST -> "Model provider rejected the request";
            case EMPTY_RESPONSE -> EMPTY_RESPONSE_MESSAGE;
            case SERVER, UNKNOWN -> PROVIDER_ERROR_MESSAGE;
        };
        return new Failed(requestId, code, message);
    }

    /**
     * Classifies a provider failure by exception type, never by scanning the message for
     * keywords. I/O failures are transient server errors eligible for fallback; illegal
     * arguments are invalid requests; everything else is unknown.
     */
    static ModelFailureKind classify(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof org.springframework.web.client
                    .HttpStatusCodeException response) {
                int status = response.getStatusCode().value();
                if (status == 401 || status == 403) {
                    return ModelFailureKind.AUTHENTICATION;
                }
                if (status == 429) {
                    return ModelFailureKind.RATE_LIMIT;
                }
                if (status >= 500) {
                    return ModelFailureKind.SERVER;
                }
                return ModelFailureKind.INVALID_REQUEST;
            }
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.io.InterruptedIOException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return ModelFailureKind.TIMEOUT;
            }
            if (current instanceof java.io.IOException) {
                return ModelFailureKind.SERVER;
            }
            if (current instanceof IllegalArgumentException) {
                return ModelFailureKind.INVALID_REQUEST;
            }
            current = current.getCause();
        }
        return ModelFailureKind.UNKNOWN;
    }
}
