package com.pulseink.controller.run;

import com.pulseink.service.campaign.QueryRunUseCase;
import com.pulseink.service.campaign.RunEvent;
import com.pulseink.service.campaign.RunEventService;
import com.pulseink.service.campaign.RunEventService.Subscriber;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * JWT-protected SSE stream for run events. The optional {@code Last-Event-ID} header is a
 * non-negative decimal long; the server replays persisted events with greater sequence inside
 * the per-runId lock and then follows the live subscription. Replay/live never lose or
 * duplicate events. Timeout, completion and errors always unsubscribe the subscriber.
 */
@RestController
public class RunEventController {

    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final QueryRunUseCase queryRunUseCase;
    private final RunEventService eventService;

    public RunEventController(QueryRunUseCase queryRunUseCase,
                              RunEventService eventService) {
        this.queryRunUseCase = Objects.requireNonNull(queryRunUseCase);
        this.eventService = Objects.requireNonNull(eventService);
    }

    @GetMapping(value = "/api/runs/{runId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable long runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        if (runId <= 0) {
            throw new IllegalArgumentException("run id must be positive");
        }
        queryRunUseCase.executionDecision(runId);
        long last = parseLastEventId(lastEventId);

        var emitter = new SseEmitter(SSE_TIMEOUT_MS);
        var lastDelivered = new AtomicLong(last);
        Subscriber subscriber = event -> {
            if (event.sequence() <= lastDelivered.get()) {
                return;
            }
            lastDelivered.set(event.sequence());
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.sequence()))
                        .name(eventName(event.type()))
                        .data(eventData(event)));
            } catch (IOException | IllegalStateException ex) {
                emitter.completeWithError(ex);
            }
        };
        eventService.replayThenSubscribe(runId, last, subscriber);
        emitter.onCompletion(() -> eventService.unsubscribe(runId, subscriber));
        emitter.onTimeout(() -> {
            eventService.unsubscribe(runId, subscriber);
            emitter.complete();
        });
        emitter.onError(ignored -> eventService.unsubscribe(runId, subscriber));
        return emitter;
    }

    private static long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value.strip());
            if (parsed < 0) {
                throw new InvalidLastEventIdException(
                        "Last-Event-ID must be a non-negative decimal long");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new InvalidLastEventIdException(
                    "Last-Event-ID must be a non-negative decimal long");
        }
    }

    /**
     * Stable typed failure for an invalid {@code Last-Event-ID} header.
     */
    public static final class InvalidLastEventIdException extends IllegalArgumentException {
        public InvalidLastEventIdException(String message) {
            super(message);
        }
    }

    private static String eventName(com.pulseink.service.campaign.RunEventType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> eventData(RunEvent event) {
        return Map.of(
                "eventVersion", RunEvent.EVENT_VERSION,
                "runId", event.runId(),
                "sequence", event.sequence(),
                "eventType", event.type().name(),
                "payload", event.payload(),
                "createdAt", event.createdAt().toString());
    }
}
