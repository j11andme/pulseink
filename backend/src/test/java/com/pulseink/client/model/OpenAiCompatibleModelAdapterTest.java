package com.pulseink.client.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.model.ModelFailureKind;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamEvent.Completed;
import com.pulseink.agent.model.ModelStreamEvent.ContentDelta;
import com.pulseink.agent.model.ModelStreamEvent.Failed;
import com.pulseink.agent.model.ModelStreamEvent.Started;
import com.pulseink.agent.model.ModelStreamEvent.Usage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class OpenAiCompatibleModelAdapterTest {

    private static final ModelRequest REQUEST =
            new ModelRequest(
                    "request-1",
                    "You are PulseInk.",
                    "Introduce the project.",
                    0.3,
                    512);

    @Test
    void mapsPromptAndSpringAiChunksToFrameworkNeutralEvents() {
        var capturedPrompt = new AtomicReference<Prompt>();
        StreamingChatModel model = prompt -> {
            capturedPrompt.set(prompt);
            return Flux.just(response("Pulse", null), response("Ink", "STOP"));
        };
        var adapter = new OpenAiCompatibleModelAdapter(model, "zhipu", "glm-5.2");
        var events = new ArrayList<ModelStreamEvent>();

        adapter.stream(REQUEST, events::add);

        assertThat(events)
                .containsExactly(
                        new Started("request-1", "zhipu", "glm-5.2"),
                        new ContentDelta("request-1", "Pulse"),
                        new ContentDelta("request-1", "Ink"),
                        new Completed("request-1", "STOP"));
        assertThat(capturedPrompt.get().getSystemMessage().getText())
                .isEqualTo("You are PulseInk.");
        assertThat(capturedPrompt.get().getUserMessage().getText())
                .isEqualTo("Introduce the project.");
        assertThat(capturedPrompt.get().getOptions().getTemperature()).isEqualTo(0.3);
        assertThat(capturedPrompt.get().getOptions().getMaxTokens()).isEqualTo(512);
        assertThat(capturedPrompt.get().getOptions().getModel()).isEqualTo("glm-5.2");
    }

    @Test
    void requestsProviderJsonModeOnlyForStructuredOutput() {
        var capturedPrompt = new AtomicReference<Prompt>();
        StreamingChatModel model = prompt -> {
            capturedPrompt.set(prompt);
            return Flux.just(response("{}", "STOP"));
        };
        var adapter = new OpenAiCompatibleModelAdapter(model, "ark", "judge-model");
        var request = new ModelRequest(
                "judge-1", "Return JSON.", "Compare.", 0.0, 512,
                ModelRequest.OutputFormat.JSON_OBJECT);

        adapter.stream(request, ignored -> {});

        var options = (org.springframework.ai.openai.OpenAiChatOptions)
                capturedPrompt.get().getOptions();
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(org.springframework.ai.openai.OpenAiChatModel
                        .ResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    void completeUsesSynchronousModelForStructuredOutput() {
        var capturedPrompt = new AtomicReference<Prompt>();
        ChatModel synchronousModel = prompt -> {
            capturedPrompt.set(prompt);
            return response("{\"decision\":\"FINAL\"}", "STOP");
        };
        StreamingChatModel streamingModel = prompt ->
                Flux.error(new AssertionError("structured completion must not use streaming"));
        var adapter = new OpenAiCompatibleModelAdapter(
                synchronousModel, streamingModel, "ark", "glm-5.2");
        var request = new ModelRequest(
                "direct-1", "Return JSON.", "Create a draft.", 0.0, 512,
                ModelRequest.OutputFormat.JSON_OBJECT);

        var completion = adapter.complete(request, Duration.ofSeconds(1));

        assertThat(completion.content()).isEqualTo("{\"decision\":\"FINAL\"}");
        assertThat(completion.providerId()).isEqualTo("ark");
        assertThat(completion.modelId()).isEqualTo("glm-5.2");
        var options = (org.springframework.ai.openai.OpenAiChatOptions)
                capturedPrompt.get().getOptions();
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(org.springframework.ai.openai.OpenAiChatModel
                        .ResponseFormat.Type.JSON_OBJECT);
    }

    @Test
    void dropsBlankDeltasAndUsesStopWhenProviderOmitsFinishReason() {
        StreamingChatModel model =
                prompt -> Flux.just(response("  ", null), response("PulseInk", null));
        var adapter = new OpenAiCompatibleModelAdapter(model, "ark", "doubao-test");
        var events = new ArrayList<ModelStreamEvent>();

        adapter.stream(REQUEST, events::add);

        assertThat(events)
                .containsExactly(
                        new Started("request-1", "ark", "doubao-test"),
                        new ContentDelta("request-1", "PulseInk"),
                        new Completed("request-1", "STOP"));
    }

    @Test
    void mapsProviderUsageToFrameworkNeutralUsageEvent() {
        StreamingChatModel model = prompt -> Flux.just(responseWithUsage(
                "PulseInk", "STOP", 12, 8));
        var adapter = new OpenAiCompatibleModelAdapter(model, "ark", "doubao-test");
        var events = new ArrayList<ModelStreamEvent>();

        adapter.stream(REQUEST, events::add);

        assertThat(events).containsSubsequence(
                new ContentDelta("request-1", "PulseInk"),
                new Usage("request-1", 12, 8),
                new Completed("request-1", "STOP"));
    }

    @Test
    void failsWhenAReasoningStreamCompletesWithoutVisibleContent() {
        StreamingChatModel model = prompt -> Flux.just(reasoningResponse(
                "The provider is still reasoning.",
                "LENGTH"));
        var adapter = new OpenAiCompatibleModelAdapter(model, "zhipu", "glm-5.2");
        var events = new ArrayList<ModelStreamEvent>();

        adapter.stream(REQUEST, events::add);

        assertThat(events)
                .containsExactly(
                        new Started("request-1", "zhipu", "glm-5.2"),
                        new Failed(
                                "request-1",
                                "MODEL_EMPTY_RESPONSE",
                                "Model output ended before a visible answer was produced; increase max tokens and retry"));
        assertThat(events.toString()).doesNotContain("still reasoning");
    }

    @Test
    void redactsProviderFailures() {
        StreamingChatModel model =
                prompt -> Flux.error(new IllegalStateException("secret-key-should-not-leak"));
        var adapter = new OpenAiCompatibleModelAdapter(model, "ark", "doubao-test");
        var events = new ArrayList<ModelStreamEvent>();

        adapter.stream(REQUEST, events::add);

        assertThat(events)
                .containsExactly(
                        new Started("request-1", "ark", "doubao-test"),
                        new Failed(
                                "request-1",
                                "MODEL_UNKNOWN",
                                "Model provider request failed"));
        assertThat(events.toString()).doesNotContain("secret-key-should-not-leak");
    }

    @Test
    void cancellationDisposesTheSpringAiSubscription() {
        var cancelled = new AtomicBoolean();
        StreamingChatModel model =
                prompt -> Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true));
        var adapter = new OpenAiCompatibleModelAdapter(model, "ark", "doubao-test");

        var handle = adapter.stream(REQUEST, ignored -> {});
        handle.cancel();

        assertThat(cancelled).isTrue();
    }

    @Test
    void classifiesFailuresByExceptionTypeNotMessageWords() {
        assertThat(OpenAiCompatibleModelAdapter.classify(
                new java.io.IOException("connection refused")))
                .isEqualTo(ModelFailureKind.SERVER);
        assertThat(OpenAiCompatibleModelAdapter.classify(
                new java.io.InterruptedIOException("stream timed out")))
                .isEqualTo(ModelFailureKind.TIMEOUT);
        assertThat(OpenAiCompatibleModelAdapter.classify(
                new IllegalArgumentException("bad request")))
                .isEqualTo(ModelFailureKind.INVALID_REQUEST);
        assertThat(OpenAiCompatibleModelAdapter.classify(
                new IllegalStateException("boom")))
                .isEqualTo(ModelFailureKind.UNKNOWN);
    }

    @Test
    void reactiveInvalidRequestFailureUsesNonRetryableTypedCode() {
        StreamingChatModel model = prompt -> Flux.error(
                new IllegalArgumentException("request body contained a secret"));
        var adapter = new OpenAiCompatibleModelAdapter(model, "ark", "doubao-test");
        var events = new ArrayList<ModelStreamEvent>();

        adapter.stream(REQUEST, events::add);

        assertThat(events).containsExactly(
                new Started("request-1", "ark", "doubao-test"),
                new Failed(
                        "request-1",
                        "MODEL_INVALID_REQUEST",
                        "Model provider rejected the request"));
        assertThat(events.toString()).doesNotContain("secret");
    }

    private static ChatResponse response(String content, String finishReason) {
        var message = new AssistantMessage(content);
        if (finishReason == null) {
            return new ChatResponse(List.of(new Generation(message)));
        }
        var metadata =
                ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(message, metadata)));
    }

    private static ChatResponse responseWithUsage(
            String content, String finishReason, int inputTokens, int outputTokens) {
        var message = new AssistantMessage(content);
        var generationMetadata =
                ChatGenerationMetadata.builder().finishReason(finishReason).build();
        var responseMetadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(inputTokens, outputTokens))
                .build();
        return new ChatResponse(
                List.of(new Generation(message, generationMetadata)), responseMetadata);
    }

    private static ChatResponse reasoningResponse(
            String reasoningContent,
            String finishReason) {
        var message = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", reasoningContent))
                .build();
        var metadata =
                ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(message, metadata)));
    }
}
