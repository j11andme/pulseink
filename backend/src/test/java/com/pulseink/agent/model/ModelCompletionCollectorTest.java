package com.pulseink.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.pulseink.agent.model.ModelStreamEvent.Completed;
import com.pulseink.agent.model.ModelStreamEvent.ContentDelta;
import com.pulseink.agent.model.ModelStreamEvent.Failed;
import com.pulseink.agent.model.ModelStreamEvent.Started;
import com.pulseink.agent.model.ModelStreamEvent.Usage;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ModelCompletionCollectorTest {

    private static final ModelRequest REQUEST =
            new ModelRequest("req-1", "system", "user", null, null);

    @Test
    void aggregatesStreamInEventOrder() {
        AgentModelPort model = (request, events) -> {
            events.accept(new Started(request.requestId(), "fake", "pulseink-fake"));
            events.accept(new ContentDelta(request.requestId(), "Pulse"));
            events.accept(new ContentDelta(request.requestId(), "Ink"));
            events.accept(new Usage(request.requestId(), 10, 20));
            events.accept(new Completed(request.requestId(), "STOP"));
            return () -> {};
        };

        var completion =
                ModelCompletionCollector.collect(model, REQUEST, Duration.ofSeconds(2));

        assertThat(completion.requestId()).isEqualTo("req-1");
        assertThat(completion.providerId()).isEqualTo("fake");
        assertThat(completion.modelId()).isEqualTo("pulseink-fake");
        assertThat(completion.content()).isEqualTo("PulseInk");
        assertThat(completion.inputTokens()).isEqualTo(10);
        assertThat(completion.outputTokens()).isEqualTo(20);
        assertThat(completion.finishReason()).isEqualTo("STOP");
    }

    @Test
    void timesOutAndCancelsHandle() {
        var cancelled = new AtomicBoolean();
        AgentModelPort model = (request, events) -> {
            events.accept(new Started(request.requestId(), "fake", "pulseink-fake"));
            return () -> cancelled.set(true);
        };

        var thrown = catchThrowable(() ->
                ModelCompletionCollector.collect(model, REQUEST, Duration.ofMillis(150)));

        assertThat(thrown).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) thrown).failureKind())
                .isEqualTo(ModelFailureKind.TIMEOUT);
        assertThat(cancelled).isTrue();
    }

    @Test
    void failedEventBecomesTypedExceptionWithoutLeakingMessage() {
        AgentModelPort model = (request, events) -> {
            events.accept(new Started(request.requestId(), "fake", "pulseink-fake"));
            events.accept(new Failed(request.requestId(), "MODEL_EMPTY_RESPONSE",
                    "secret provider detail"));
            return () -> {};
        };

        var thrown = catchThrowable(() ->
                ModelCompletionCollector.collect(model, REQUEST, Duration.ofSeconds(2)));

        assertThat(thrown).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) thrown).failureKind())
                .isEqualTo(ModelFailureKind.EMPTY_RESPONSE);
        assertThat(thrown.getMessage()).doesNotContain("secret provider detail");
    }

    @Test
    void emptyContentWithoutCompletedIsRejected() {
        AgentModelPort model = (request, events) -> {
            events.accept(new Started(request.requestId(), "fake", "pulseink-fake"));
            events.accept(new Completed(request.requestId(), "STOP"));
            return () -> {};
        };

        var thrown = catchThrowable(() ->
                ModelCompletionCollector.collect(model, REQUEST, Duration.ofSeconds(2)));

        assertThat(thrown).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) thrown).failureKind())
                .isEqualTo(ModelFailureKind.EMPTY_RESPONSE);
    }

    @Test
    void defaultCompleteMethodOnPortWorksWithLambdaAdapters() {
        AgentModelPort model = (request, events) -> {
            events.accept(new Started(request.requestId(), "fake", "pulseink-fake"));
            events.accept(new ContentDelta(request.requestId(), "ok"));
            events.accept(new Completed(request.requestId(), "STOP"));
            return () -> {};
        };

        var completion = model.complete(REQUEST, Duration.ofSeconds(2));
        assertThat(completion.content()).isEqualTo("ok");
        assertThat(completion.finishReason()).isEqualTo("STOP");
    }

    @Test
    void providerIdAndModelIdRequireStartedEvent() {
        AgentModelPort model = (request, events) -> {
            events.accept(new ContentDelta(request.requestId(), "ok"));
            events.accept(new Completed(request.requestId(), "STOP"));
            return () -> {};
        };

        var completion =
                ModelCompletionCollector.collect(model, REQUEST, Duration.ofSeconds(2));
        assertThat(completion.providerId()).isBlank();
        assertThat(completion.modelId()).isBlank();
    }
}
