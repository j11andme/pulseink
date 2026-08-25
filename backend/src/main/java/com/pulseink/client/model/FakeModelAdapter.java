package com.pulseink.client.model;

import com.pulseink.agent.model.AgentModelPort;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelStreamEvent;
import com.pulseink.agent.model.ModelStreamEvent.Completed;
import com.pulseink.agent.model.ModelStreamEvent.ContentDelta;
import com.pulseink.agent.model.ModelStreamEvent.Failed;
import com.pulseink.agent.model.ModelStreamEvent.Started;
import com.pulseink.agent.model.ModelStreamEvent.Usage;
import com.pulseink.agent.model.ModelStreamHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class FakeModelAdapter implements AgentModelPort {

    private static final String PROVIDER = "fake";
    private static final String MODEL = "pulseink-fake";
    private static final List<String> CONTENT_CHUNKS = List.of("Pulse", "Ink");
    private static final Pattern CONTENT_VERSION_ID =
            Pattern.compile("\\\"contentVersionId\\\"\\s*:\\s*(\\d+)");
    private static final Pattern PUBLICATION_ID =
            Pattern.compile("\\\"publicationId\\\"\\s*:\\s*(\\d+)");
    private static final Pattern CHANNEL =
            Pattern.compile("\\\"channel\\\"\\s*:\\s*\\\"([A-Z_]+)\\\"");
    private static final Pattern METRIC_DATE =
            Pattern.compile("\\\"metricDate\\\"\\s*:\\s*\\\"(\\d{4}-\\d{2}-\\d{2})\\\"");

    private final Duration eventDelay;
    private final List<Scene> scenes;
    private final AtomicInteger callIndex = new AtomicInteger();

    public FakeModelAdapter() {
        this(Duration.ofMillis(120));
    }

    /** Zero-delay deterministic adapter for offline evaluation and focused tests. */
    public static FakeModelAdapter fast() {
        return new FakeModelAdapter(Duration.ZERO);
    }

    FakeModelAdapter(Duration eventDelay) {
        this(eventDelay, List.of());
    }

    /**
     * Scriptable constructor for engine tests: each {@code stream} call consumes the next scene.
     * When the script is exhausted, any further call fails loudly.
     */
    public FakeModelAdapter(List<Scene> scenes) {
        this(Duration.ZERO, scenes);
    }

    private FakeModelAdapter(Duration eventDelay, List<Scene> scenes) {
        this.eventDelay = Objects.requireNonNull(eventDelay);
        this.scenes = List.copyOf(scenes);
    }

    @Override
    public ModelStreamHandle stream(
            ModelRequest request,
            Consumer<ModelStreamEvent> eventConsumer) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(eventConsumer);

        var cancelled = new AtomicBoolean();
        var worker = Thread.ofVirtual()
                .name("pulseink-fake-model-" + request.requestId())
                .unstarted(() -> emitEvents(request, eventConsumer, cancelled));
        worker.start();
        return () -> {
            cancelled.set(true);
            worker.interrupt();
        };
    }

    private void emitEvents(
            ModelRequest request,
            Consumer<ModelStreamEvent> eventConsumer,
            AtomicBoolean cancelled) {
        List<ModelStreamEvent> sceneEvents = scenes.isEmpty()
                ? defaultScene(request)
                : nextScene(request);
        for (var event : sceneEvents) {
            if (!emit(event, eventConsumer, cancelled)) {
                return;
            }
        }
    }

    private List<ModelStreamEvent> nextScene(ModelRequest request) {
        int index = callIndex.getAndIncrement();
        if (index >= scenes.size()) {
            throw new IllegalStateException(
                    "fake model script exhausted after " + scenes.size() + " calls");
        }
        return scenes.get(index).events(request);
    }

    private List<ModelStreamEvent> defaultScene(ModelRequest request) {
        if (request.systemPrompt() != null
                && request.systemPrompt().contains("PulseInk Evaluation Judge")) {
            return scene(request, """
                    {"schemaVersion":1,"candidateAScore":0.75,"candidateBScore":0.75}
                    """);
        }
        if (request.systemPrompt() != null
                && request.systemPrompt().contains("PulseInk Insight Consolidator")) {
            return scene(request, insightCandidateJson(request.userPrompt()));
        }
        if (request.systemPrompt() != null
                && (request.systemPrompt().contains("PlanSpec")
                        || request.systemPrompt().contains("PulseInk Planner"))) {
            return scene(request, plannerJson());
        }
        if (request.systemPrompt() != null
                && request.systemPrompt().contains("structured decision protocol")) {
            if (request.systemPrompt().contains("PulseInk Researcher")) {
                return scene(request, """
                        {"decision":"TOOL_CALL","decisionSummary":"search knowledge",
                         "toolCall":{"qualifiedName":"builtin.knowledge_search",
                                     "arguments":{"query":"brand color"}}}
                        """);
            }
            if (request.systemPrompt().contains("PulseInk Strategist")) {
                return scene(request, """
                        {"decision":"FINAL","decisionSummary":"strategy",
                         "artifacts":[{"type":"CONTENT_STRATEGY",
                                       "content":{"strategy":"unified"},
                                       "sourceRefs":[]}]}
                        """);
            }
            if (request.systemPrompt().contains("PulseInk Creator")) {
                return scene(request, """
                        {"decision":"FINAL","decisionSummary":"draft",
                         "artifacts":[{"type":"CONTENT_DRAFT",
                                       "content":{"draft":"PulseInk"},
                                       "sourceRefs":[]}]}
                        """);
            }
            if (request.systemPrompt().contains("PulseInk Reviewer")) {
                return scene(request, """
                         {"decision":"FINAL","decisionSummary":"review",
                          "artifacts":[{"type":"REVIEW_REPORT",
                                       "content":{"passed":true,"issues":[]},
                                       "sourceRefs":[]}]}
                        """);
            }
            return structuredScene(request);
        }
        return List.of(
                new Started(request.requestId(), PROVIDER, MODEL),
                new ContentDelta(request.requestId(), "Pulse"),
                new ContentDelta(request.requestId(), "Ink"),
                new Completed(request.requestId(), "STOP"));
    }

    private static List<ModelStreamEvent> scene(ModelRequest request, String content) {
        return List.of(
                new Started(request.requestId(), PROVIDER, MODEL),
                new ContentDelta(request.requestId(), content),
                new Usage(request.requestId(), 10, 20),
                new Completed(request.requestId(), "STOP"));
    }

    private static String plannerJson() {
        return """
                {"schemaVersion":1,"tasks":[
                  {"taskId":"strategy-main","role":"STRATEGIST","objective":"form strategy",
                   "dependsOn":[],"requiredArtifactTypes":[],
                   "outputArtifactType":"CONTENT_STRATEGY","access":"READ_ONLY"},
                  {"taskId":"create-main","role":"CREATOR","objective":"write draft",
                   "dependsOn":["strategy-main"],"requiredArtifactTypes":[],
                   "outputArtifactType":"CONTENT_DRAFT","access":"READ_ONLY"}]}
                """;
    }

    private static String insightCandidateJson(String userPrompt) {
        long contentVersionId = firstLong(CONTENT_VERSION_ID, userPrompt);
        long publicationId = firstLong(PUBLICATION_ID, userPrompt);
        String channel = firstText(CHANNEL, userPrompt);
        var metricDates = new ArrayList<String>();
        var matcher = METRIC_DATE.matcher(userPrompt);
        while (matcher.find()) {
            metricDates.add(matcher.group(1));
        }
        if (metricDates.isEmpty()) {
            throw new IllegalArgumentException("fake insight snapshot must contain metrics");
        }
        metricDates.sort(String::compareTo);
        return """
                {"schemaVersion":1,"category":"CHANNEL_PATTERN",
                 "title":"已发布内容的渠道表现基线",
                 "insightText":"该渠道已有经人工批准并发布的内容，可作为后续内容策略的历史参考。",
                 "scopeType":"CHANNEL","scopeValue":"%s",
                 "applicableChannels":["%s"],
                 "evidenceRefs":[{"contentVersionId":%d,"publicationId":%d,
                                  "metricFrom":"%s","metricTo":"%s"}],
                 "confidence":0.72,"limitations":["仅基于当前活动的沙箱反馈样本"]}
                """.formatted(channel, channel, contentVersionId, publicationId,
                        metricDates.getFirst(), metricDates.getLast());
    }

    private static long firstLong(Pattern pattern, String text) {
        return Long.parseLong(firstText(pattern, text));
    }

    private static String firstText(Pattern pattern, String text) {
        var matcher = pattern.matcher(Objects.requireNonNullElse(text, ""));
        if (!matcher.find()) {
            throw new IllegalArgumentException("fake insight snapshot is incomplete");
        }
        return matcher.group(1);
    }

    private static List<ModelStreamEvent> structuredScene(ModelRequest request) {
        return List.of(
                new Started(request.requestId(), PROVIDER, MODEL),
                new ContentDelta(request.requestId(), """
                        {"decision":"FINAL","decisionSummary":"fake draft ready",
                         "artifacts":[{"type":"CONTENT_DRAFT",
                         "content":{"title":"PulseInk","body":"PulseInk draft"}}]}
                        """),
                new Usage(request.requestId(), 10, 20),
                new Completed(request.requestId(), "STOP"));
    }

    private boolean emit(
            ModelStreamEvent event,
            Consumer<ModelStreamEvent> eventConsumer,
            AtomicBoolean cancelled) {
        try {
            if (!eventDelay.isZero()) {
                Thread.sleep(eventDelay);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (cancelled.get()) {
            return false;
        }
        eventConsumer.accept(event);
        return true;
    }

    /**
     * One scripted model call. The default scene streams the given content as a single delta
     * with a STOP completion; a non-null failure produces a {@link Failed} event instead.
     */
    public record Scene(
            String content,
            String failureCode,
            String failureMessage,
            long inputTokens,
            long outputTokens) {

        public static Scene of(String content) {
            return new Scene(content, null, null, 10, 20);
        }

        public static Scene of(String content, long inputTokens, long outputTokens) {
            return new Scene(content, null, null, inputTokens, outputTokens);
        }

        public static Scene failure(String code, String message) {
            return new Scene("", code, message, 0, 0);
        }

        List<ModelStreamEvent> events(ModelRequest request) {
            if (failureCode != null) {
                return List.of(
                        new Started(request.requestId(), PROVIDER, MODEL),
                        new Failed(request.requestId(), failureCode, failureMessage));
            }
            return List.of(
                    new Started(request.requestId(), PROVIDER, MODEL),
                    new ContentDelta(request.requestId(), content),
                    new Usage(request.requestId(), inputTokens, outputTokens),
                    new Completed(request.requestId(), "STOP"));
        }
    }
}
