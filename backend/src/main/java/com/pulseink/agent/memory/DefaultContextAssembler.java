package com.pulseink.agent.memory;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.orchestration.ArtifactContextRenderer;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.service.memory.ApprovedInsightHit;
import com.pulseink.service.memory.CampaignEpisodicMemory;
import com.pulseink.service.memory.MemoryPort;
import com.pulseink.service.memory.RunWorkingMemory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic role context assembly: fixed section order, VALID-only artifacts via the
 * shared renderer, scope/channel matched APPROVED insights, stable dedup/ranking and a code
 * point budget that never splits a UTF-16 surrogate pair.
 */
public final class DefaultContextAssembler implements ContextAssembler {

    private static final int DEFAULT_MAX_CODE_POINTS = 12_000;
    private static final int INSIGHT_QUERY_MAX_CODE_POINTS = 500;

    private final MemoryPort memoryPort;
    private final ArtifactContextRenderer artifactRenderer;

    public DefaultContextAssembler(MemoryPort memoryPort,
                                   ArtifactContextRenderer artifactRenderer) {
        this.memoryPort = Objects.requireNonNull(memoryPort);
        this.artifactRenderer = Objects.requireNonNull(artifactRenderer);
    }

    @Override
    public AgentContext assemble(ContextAssemblyRequest request) {
        var policy = RoleContextPolicy.forProfile(request.profile());
        int budget = request.maxCodePoints() > 0
                ? request.maxCodePoints() : DEFAULT_MAX_CODE_POINTS;
        var sections = new LinkedHashMap<ContextSection, String>();
        var sources = new ArrayList<ContextSource>();
        boolean cacheHit = false;

        if (policy.includes(ContextSection.BRIEF)) {
            sections.put(ContextSection.BRIEF, request.campaignBrief());
        }
        if (policy.includes(ContextSection.CURRENT_OBJECTIVE)) {
            sections.put(ContextSection.CURRENT_OBJECTIVE, request.currentObjective());
        }
        if (policy.includes(ContextSection.WORKING_MEMORY)) {
            var result = memoryPort.loadRunWorkingMemory(request.runId());
            cacheHit = result.cacheHit();
            sections.put(ContextSection.WORKING_MEMORY, renderWorkingMemory(result.memory()));
        }
        if (policy.includes(ContextSection.DEPENDENCY_ARTIFACTS)) {
            sections.put(ContextSection.DEPENDENCY_ARTIFACTS,
                    artifactRenderer.renderArtifacts(request.currentArtifacts()));
        }
        if (policy.includes(ContextSection.EPISODIC_SUMMARY)) {
            var episode = memoryPort.loadCampaignEpisode(request.runId());
            sections.put(ContextSection.EPISODIC_SUMMARY, renderEpisode(episode));
            sources.add(new ContextSource("EPISODIC", "campaign-facts",
                    "campaign:" + episode.campaignId() + ",run:" + episode.runId()));
        }
        if (policy.includes(ContextSection.APPROVED_INSIGHTS)) {
            var hits = matchedInsights(request, policy.maxApprovedInsights());
            sections.put(ContextSection.APPROVED_INSIGHTS, renderInsights(hits));
            for (var hit : hits) {
                sources.add(new ContextSource("APPROVED_INSIGHT",
                        "insight:" + hit.insightId(), "campaign:" + hit.sourceCampaignId()));
            }
        }
        if (policy.includes(ContextSection.SOURCE_LABELS)) {
            sections.put(ContextSection.SOURCE_LABELS, renderSources(sources,
                    request.currentArtifacts()));
        }

        var builder = new StringBuilder();
        for (ContextSection section : ContextSection.values()) {
            String content = sections.get(section);
            if (content == null || content.isEmpty()) {
                continue;
            }
            builder.append('[').append(section.name()).append("]\n")
                    .append(content).append('\n');
        }
        String rendered = builder.toString();
        boolean truncated = rendered.codePointCount(0, rendered.length()) > budget;
        return new AgentContext(truncate(rendered, budget), List.copyOf(sources),
                cacheHit, truncated);
    }

    private List<ApprovedInsightHit> matchedInsights(ContextAssemblyRequest request,
                                                     int topK) {
        String query = insightQuery(request);
        var channels = request.campaignChannels();
        var matched = new LinkedHashMap<Long, ApprovedInsightHit>();
        mergeMatched(matched, memoryPort.searchApprovedInsights(query, null, topK), channels);
        channels.stream().distinct().sorted().forEach(channel ->
                mergeMatched(matched,
                        memoryPort.searchApprovedInsights(query, channel, topK), channels));
        return matched.values().stream()
                .limit(topK)
                .toList();
    }

    private static void mergeMatched(
            LinkedHashMap<Long, ApprovedInsightHit> matched,
            List<ApprovedInsightHit> candidates,
            List<CampaignChannel> campaignChannels) {
        candidates.stream()
                .filter(hit -> hit.scopeType()
                                == com.pulseink.domain.memory.InsightScopeType.WORKSPACE
                        || campaignChannels.isEmpty()
                        || hit.applicableChannels().stream()
                                .anyMatch(campaignChannels::contains))
                .forEach(hit -> matched.putIfAbsent(hit.insightId(), hit));
    }

    private static String insightQuery(ContextAssemblyRequest request) {
        String raw = request.campaignBrief() + " " + request.currentObjective();
        if (raw.codePointCount(0, raw.length()) <= INSIGHT_QUERY_MAX_CODE_POINTS) {
            return raw;
        }
        return raw.substring(0, raw.offsetByCodePoints(0, INSIGHT_QUERY_MAX_CODE_POINTS));
    }

    private static String renderWorkingMemory(RunWorkingMemory memory) {
        var builder = new StringBuilder();
        builder.append("checkpointType=").append(memory.checkpointType())
                .append(" lastPersistedEventSequence=")
                .append(memory.lastPersistedEventSequence())
                .append(" budget=").append(memory.budgetSnapshot())
                .append('\n');
        for (var artifact : memory.validArtifacts()) {
            builder.append("artifact taskId=").append(artifact.taskId())
                    .append(" type=").append(artifact.type().name())
                    .append(" version=").append(artifact.artifactVersion())
                    .append(" summary=").append(artifact.contentSummary())
                    .append('\n');
        }
        return builder.toString();
    }

    private static String renderEpisode(CampaignEpisodicMemory episode) {
        var builder = new StringBuilder();
        builder.append("campaignId=").append(episode.campaignId())
                .append(" approvedVersions=").append(episode.approvedVersions().size())
                .append(" publishedPosts=").append(episode.publications().size())
                .append(" metricRows=").append(episode.metrics().size())
                .append('\n');
        for (var version : episode.approvedVersions()) {
            builder.append("approved versionId=").append(version.contentVersionId())
                    .append(" versionNo=").append(version.versionNo())
                    .append(" origin=").append(version.origin())
                    .append(" editedBy=").append(version.editedBy())
                    .append(" approvedBy=").append(version.approvedBy())
                    .append(" approvalComment=").append(version.approvalComment())
                    .append(" content=").append(new TreeMap<>(version.content()))
                    .append('\n');
        }
        for (var post : episode.publications()) {
            builder.append("published channel=").append(post.channel().name())
                    .append(" at=").append(post.publishedAt())
                    .append(" receipt=").append(new TreeMap<>(post.receipt()))
                    .append('\n');
        }
        for (var metric : episode.metrics()) {
            builder.append("metric date=").append(metric.metricDate())
                    .append(" views=").append(metric.views())
                    .append(" clicks=").append(metric.clicks())
                    .append(" likes=").append(metric.likes())
                    .append('\n');
        }
        return builder.toString();
    }

    private static String renderInsights(List<ApprovedInsightHit> hits) {
        var builder = new StringBuilder();
        if (hits.isEmpty()) {
            return "none\n";
        }
        for (var hit : hits) {
            builder.append("insightId=").append(hit.insightId())
                    .append(" sourceCampaignId=").append(hit.sourceCampaignId())
                    .append(" scope=").append(hit.scopeType().name())
                    .append('/').append(hit.scopeValue())
                    .append(" channels=").append(hit.applicableChannels())
                    .append(" confidence=").append(hit.confidence())
                    .append('\n')
                    .append("title: ").append(hit.title())
                    .append('\n')
                    .append("insight: ").append(hit.insightText())
                    .append('\n');
        }
        return builder.toString();
    }

    private static String renderSources(List<ContextSource> sources,
                                        List<AgentArtifact> artifacts) {
        var builder = new StringBuilder();
        for (var source : sources) {
            builder.append(source.kind()).append(' ').append(source.label())
                    .append(" -> ").append(source.referenceId())
                    .append('\n');
        }
        for (var artifact : artifacts) {
            if (artifact.status()
                    != com.pulseink.agent.artifact.ArtifactStatus.VALID) {
                continue;
            }
            builder.append("ARTIFACT ").append(artifact.artifactId())
                    .append(" -> refs=").append(artifact.sourceRefs())
                    .append('\n');
        }
        return builder.toString();
    }

    private static String truncate(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        if (maxCodePoints <= 3) {
            return ".".repeat(maxCodePoints);
        }
        int end = text.offsetByCodePoints(0, Math.max(0, maxCodePoints - 3));
        return text.substring(0, end) + "...";
    }
}
