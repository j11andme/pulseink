package com.pulseink.agent.model;

import com.pulseink.agent.model.ModelStreamEvent.Completed;
import com.pulseink.agent.model.ModelStreamEvent.ContentDelta;
import com.pulseink.agent.model.ModelStreamEvent.Failed;
import com.pulseink.agent.model.ModelStreamEvent.Started;
import com.pulseink.agent.model.ModelStreamEvent.Usage;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Collects a streaming model call into a single {@link ModelCompletion}. Timeout cancels the
 * stream handle; {@link Failed} events become typed {@link ModelCallException}s whose messages
 * never leak provider details.
 */
public final class ModelCompletionCollector {

    private ModelCompletionCollector() {
    }

    public static ModelCompletion collect(
            AgentModelPort model,
            ModelRequest request,
            Duration timeout) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        var state = new State();
        var completed = new CountDownLatch(1);
        var failure = new AtomicReference<ModelCallException>();
        var handle = model.stream(request, event -> {
            switch (event) {
                case Started started -> {
                    state.provider = started.provider();
                    state.model = started.model();
                }
                case ContentDelta delta -> state.content.append(delta.content());
                case Usage usage -> {
                    state.inputTokens = usage.inputTokens();
                    state.outputTokens = usage.outputTokens();
                }
                case Completed done -> {
                    state.finishReason = done.finishReason();
                    completed.countDown();
                }
                case Failed failed -> {
                    failure.set(toException(failed));
                    completed.countDown();
                }
            }
        });

        boolean finished;
        try {
            finished = completed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            handle.cancel();
            throw new ModelCallException(
                    ModelFailureKind.UNKNOWN, "model call was interrupted");
        }
        if (!finished) {
            handle.cancel();
            throw new ModelCallException(
                    ModelFailureKind.TIMEOUT, "model call timed out after " + timeout);
        }
        var failed = failure.get();
        if (failed != null) {
            throw failed;
        }
        if (state.content.isEmpty()) {
            throw new ModelCallException(
                    ModelFailureKind.EMPTY_RESPONSE, "model returned empty content");
        }
        return new ModelCompletion(
                request.requestId(),
                state.provider,
                state.model,
                state.content.toString(),
                state.inputTokens,
                state.outputTokens,
                state.finishReason);
    }

    private static ModelCallException toException(Failed failed) {
        var kind = switch (failed.code()) {
            case "MODEL_RATE_LIMIT" -> ModelFailureKind.RATE_LIMIT;
            case "MODEL_TIMEOUT" -> ModelFailureKind.TIMEOUT;
            case "MODEL_EMPTY_RESPONSE" -> ModelFailureKind.EMPTY_RESPONSE;
            case "MODEL_PROVIDER_ERROR" -> ModelFailureKind.SERVER;
            case "MODEL_AUTHENTICATION_ERROR" -> ModelFailureKind.AUTHENTICATION;
            case "MODEL_INVALID_REQUEST" -> ModelFailureKind.INVALID_REQUEST;
            case "MODEL_UNKNOWN" -> ModelFailureKind.UNKNOWN;
            default -> ModelFailureKind.UNKNOWN;
        };
        return new ModelCallException(kind, "model call failed: " + failed.code());
    }

    private static final class State {
        String provider = "";
        String model = "";
        final StringBuilder content = new StringBuilder();
        long inputTokens;
        long outputTokens;
        String finishReason = "STOP";
    }
}
