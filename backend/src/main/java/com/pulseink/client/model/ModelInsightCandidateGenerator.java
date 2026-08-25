package com.pulseink.client.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelCallException;
import com.pulseink.agent.model.ModelCompletion;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.model.ModelRequest;
import com.pulseink.agent.model.ModelRouter;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.service.memory.GeneratedInsight;
import com.pulseink.service.memory.InsightCandidateGenerator;
import com.pulseink.service.memory.InsightErrorCode;
import com.pulseink.service.memory.InsightException;
import com.pulseink.service.memory.InsightSourceSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Model-backed one-shot insight generator over the existing ModelRouter/runtime ModelPolicy.
 * One normal call plus at most one format-repair call; every other failure maps to a stable
 * insight error and never fabricates a candidate.
 */
public final class ModelInsightCandidateGenerator implements InsightCandidateGenerator {

    private static final String SYSTEM_PROMPT = """
            You are PulseInk Insight Consolidator.
            Based only on the campaign facts snapshot, return exactly one InsightCandidate JSON
            object, without Markdown fences and without any extra text:
            {"schemaVersion":1,"category":"STYLE_PREFERENCE|CHANNEL_PATTERN|REUSABLE_CASE",
             "title":"short title","insightText":"reusable bounded business conclusion",
             "scopeType":"WORKSPACE|CHANNEL","scopeValue":"",
             "applicableChannels":["BLOG","SOCIAL","SHORT_VIDEO"],
             "evidenceRefs":[{"contentVersionId":1,"publicationId":2,
                              "metricFrom":"2026-08-01","metricTo":"2026-08-07"}],
             "confidence":0.78,"limitations":["..."]}
            Every evidenceRef must reference facts that exist in the snapshot. Never output
            hidden reasoning or raw prompts.
            """;
    private static final String REPAIR_SUFFIX =
            "\nYour previous output was not valid. Return exactly one valid InsightCandidate "
                    + "JSON object only, without fences.";

    private final ModelRouter router;
    private final ModelPolicy modelPolicy;
    private final int maxOutputTokens;
    private final Duration completionTimeout;
    private final ObjectMapper objectMapper;
    private final JacksonInsightCandidateParser parser;

    public ModelInsightCandidateGenerator(ModelRouter router,
                                          ModelPolicy modelPolicy,
                                          int maxOutputTokens,
                                          Duration completionTimeout,
                                          ObjectMapper objectMapper) {
        this.router = Objects.requireNonNull(router);
        this.modelPolicy = Objects.requireNonNull(modelPolicy);
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
        this.maxOutputTokens = maxOutputTokens;
        this.completionTimeout = Objects.requireNonNull(completionTimeout);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.parser = new JacksonInsightCandidateParser();
    }

    @Override
    public GeneratedInsight generate(InsightSourceSnapshot source) {
        var profile = AgentProfile.unified("insight-generator", Set.of(), modelPolicy,
                new ExecutionBudget(2, 0, maxOutputTokens * 2L, 1, 1,
                        Instant.now().plus(Duration.ofMinutes(5))));
        var route = route(profile);
        String requestId = "insight-" + UUID.randomUUID();
        String userPrompt = "Campaign facts snapshot:\n" + snapshotJson(source)
                + "\nReturn exactly one InsightCandidate JSON object.";

        ModelCompletion completion = call(route, requestId, userPrompt, false);
        try {
            return parser.parse(completion.content(), source);
        } catch (InsightException firstInvalid) {
            ModelCompletion repaired = call(route, requestId, userPrompt, true);
            return parser.parse(repaired.content(), source);
        }
    }

    private ModelCompletion call(com.pulseink.agent.model.ModelRoute route,
                                 String requestId, String userPrompt, boolean repair) {
        try {
            return route.modelPort().complete(new ModelRequest(
                    requestId,
                    SYSTEM_PROMPT,
                    repair ? userPrompt + REPAIR_SUFFIX : userPrompt,
                    null,
                    maxOutputTokens,
                    ModelRequest.OutputFormat.JSON_OBJECT,
                    completionTimeout), completionTimeout);
        } catch (ModelCallException failure) {
            throw new InsightException(InsightErrorCode.INSIGHT_MODEL_FAILURE,
                    "insight model call failed", failure);
        }
    }

    private com.pulseink.agent.model.ModelRoute route(AgentProfile profile) {
        try {
            return router.route(profile, Set.of());
        } catch (IllegalStateException noRoute) {
            throw new InsightException(InsightErrorCode.INSIGHT_MODEL_FAILURE,
                    "no model route available for insight generation");
        }
    }

    private String snapshotJson(InsightSourceSnapshot source) {
        var root = new LinkedHashMap<String, Object>();
        root.put("runId", source.runId());
        root.put("campaignId", source.campaignId());
        var versions = new ArrayList<Map<String, Object>>();
        for (var version : source.approvedVersions()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("contentVersionId", version.contentVersionId());
            item.put("versionNo", version.versionNo());
            item.put("content", version.content());
            item.put("origin", version.origin());
            item.put("editedBy", version.editedBy());
            item.put("approvedBy", version.approvedBy());
            item.put("approvalComment", version.approvalComment());
            item.put("approvedAt", version.approvedAt().toString());
            versions.add(item);
        }
        root.put("approvedVersions", versions);
        var posts = new ArrayList<Map<String, Object>>();
        for (var post : source.publications()) {
            posts.add(Map.of(
                    "publicationId", post.publicationId(),
                    "contentVersionId", post.contentVersionId(),
                    "channel", post.channel().name(),
                    "publishedAt", post.publishedAt().toString(),
                    "receipt", post.receipt()));
        }
        root.put("publications", posts);
        var metrics = new ArrayList<Map<String, Object>>();
        for (var metric : source.metrics()) {
            metrics.add(Map.of(
                    "publicationId", metric.publicationId(),
                    "metricDate", metric.metricDate().toString(),
                    "views", metric.views(),
                    "clicks", metric.clicks(),
                    "likes", metric.likes()));
        }
        root.put("metrics", metrics);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new InsightException(InsightErrorCode.INSIGHT_MODEL_FAILURE,
                    "insight snapshot cannot be serialized", exception);
        }
    }
}
