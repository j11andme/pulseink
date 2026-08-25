package com.pulseink.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseink.agent.artifact.AgentArtifact;
import com.pulseink.agent.artifact.ArtifactStatus;
import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.budget.BudgetSnapshot;
import com.pulseink.agent.budget.ExecutionBudget;
import com.pulseink.agent.model.ModelPolicy;
import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.orchestration.ArtifactContextRenderer;
import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.memory.InsightCategory;
import com.pulseink.domain.memory.InsightScopeType;
import com.pulseink.service.memory.ApprovedInsightHit;
import com.pulseink.service.memory.CampaignEpisodicMemory;
import com.pulseink.service.memory.MemoryPort;
import com.pulseink.service.memory.RunWorkingMemory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-08-14T08:00:00Z");

    private FakeMemoryPort memoryPort;
    private DefaultContextAssembler assembler;

    @BeforeEach
    void setUp() {
        memoryPort = new FakeMemoryPort();
        assembler = new DefaultContextAssembler(
                memoryPort, new ArtifactContextRenderer(12_000));
    }

    @Test
    void reviewerGetsDraftEvidenceAndRulesButNeverCreatorReasoningOrInsights() {
        memoryPort.workingMemory = new RunWorkingMemory(2L, "ARTIFACT", 1, 0, 5L, NOW,
                List.of(summary("creator-reasoning-SECRET")), BudgetSnapshot.ZERO);
        memoryPort.insights = List.of(hit(1L, "SECRET-INSIGHT"));
        var draft = AgentArtifact.create("draft-1", 2L, "create-main", ArtifactType.CONTENT_DRAFT,
                1, Map.of("draft", "visible-content"), List.of("source-1"), NOW);

        var context = assembler.assemble(request(AgentRole.REVIEWER, List.of(draft)));

        assertThat(context.renderedText()).contains("[DEPENDENCY_ARTIFACTS]")
                .contains("visible-content")
                .doesNotContain("creator-reasoning-SECRET")
                .doesNotContain("SECRET-INSIGHT")
                .doesNotContain("[WORKING_MEMORY]")
                .doesNotContain("[APPROVED_INSIGHTS]");
    }

    @Test
    void strategistAndCreatorReceiveOnlyScopeMatchedApprovedInsights() {
        memoryPort.insights = List.of(
                hit(11L, "WORKSPACE-风格偏好", InsightScopeType.WORKSPACE, "",
                        List.of(CampaignChannel.BLOG)),
                hit(12L, "SOCIAL-渠道规律", InsightScopeType.CHANNEL, "SOCIAL",
                        List.of(CampaignChannel.SOCIAL)));

        var blogContext = assembler.assemble(request(AgentRole.STRATEGIST, List.of(),
                List.of(CampaignChannel.BLOG)));
        assertThat(blogContext.renderedText())
                .contains("WORKSPACE-风格偏好")
                .contains("insightId=11")
                .contains("sourceCampaignId=7")
                .doesNotContain("SOCIAL-渠道规律");

        var socialContext = assembler.assemble(request(AgentRole.STRATEGIST, List.of(),
                List.of(CampaignChannel.SOCIAL)));
        assertThat(socialContext.renderedText()).contains("SOCIAL-渠道规律");
    }

    @Test
    void channelSpecificSearchPreventsOtherChannelsFromCrowdingOutRelevantInsights() {
        memoryPort.insights = List.of(
                hit(21L, "SOCIAL-高分但无关", InsightScopeType.CHANNEL, "SOCIAL",
                        List.of(CampaignChannel.SOCIAL)));
        memoryPort.insightsByChannel.put(CampaignChannel.BLOG, List.of(
                hit(22L, "BLOG-相关洞察", InsightScopeType.CHANNEL, "BLOG",
                        List.of(CampaignChannel.BLOG))));

        var context = assembler.assemble(request(AgentRole.STRATEGIST, List.of(),
                List.of(CampaignChannel.BLOG)));

        assertThat(context.renderedText())
                .contains("BLOG-相关洞察")
                .doesNotContain("SOCIAL-高分但无关");
        assertThat(memoryPort.searchedChannels)
                .containsExactly(null, CampaignChannel.BLOG);
    }

    @Test
    void invalidatedArtifactsNeverEnterTheContext() {
        var valid = AgentArtifact.create("draft-v2", 2L, "create-main",
                ArtifactType.CONTENT_DRAFT, 2, Map.of("draft", "new-version"),
                List.of(), NOW);
        var invalidated = AgentArtifact.create("draft-v1", 2L, "create-main",
                ArtifactType.CONTENT_DRAFT, 1, Map.of("draft", "old-version"),
                List.of(), NOW).withStatus(ArtifactStatus.INVALIDATED);

        var context = assembler.assemble(request(
                AgentRole.REVIEWER, List.of(valid, invalidated)));

        assertThat(context.renderedText()).contains("new-version")
                .doesNotContain("old-version");
    }

    @Test
    void sectionOrderIsFixedAndOutputIsDeterministic() {
        memoryPort.workingMemory = new RunWorkingMemory(2L, "ARTIFACT", 1, 0, 5L, NOW,
                List.of(summary("memory-marker")), BudgetSnapshot.ZERO);
        memoryPort.insights = List.of(hit(1L, "INSIGHT-MARKER"));
        memoryPort.episode = new CampaignEpisodicMemory(1L, 2L,
                List.of(new com.pulseink.service.memory.InsightSourceSnapshot.ApprovedVersion(
                        11L, 1, Map.of("title", "T"))),
                List.of(), List.of());
        var draft = AgentArtifact.create("draft-1", 2L, "create-main",
                ArtifactType.CONTENT_DRAFT, 1, Map.of("draft", "content"),
                List.of("source-1"), NOW);

        var first = assembler.assemble(request(null, List.of(draft)));
        var second = assembler.assemble(request(null, List.of(draft)));

        assertThat(first.renderedText()).isEqualTo(second.renderedText());
        String text = first.renderedText();
        assertThat(text.indexOf("[BRIEF]")).isLessThan(text.indexOf("[CURRENT_OBJECTIVE]"));
        assertThat(text.indexOf("[CURRENT_OBJECTIVE]"))
                .isLessThan(text.indexOf("[WORKING_MEMORY]"));
        assertThat(text.indexOf("[WORKING_MEMORY]"))
                .isLessThan(text.indexOf("[DEPENDENCY_ARTIFACTS]"));
        assertThat(text.indexOf("[DEPENDENCY_ARTIFACTS]"))
                .isLessThan(text.indexOf("[EPISODIC_SUMMARY]"));
        assertThat(text.indexOf("[EPISODIC_SUMMARY]"))
                .isLessThan(text.indexOf("[APPROVED_INSIGHTS]"));
        assertThat(text.indexOf("[APPROVED_INSIGHTS]"))
                .isLessThan(text.indexOf("[SOURCE_LABELS]"));
    }

    @Test
    void chineseTextIsTruncatedInsideTheCodePointBudget() {
        String longBrief = "这是一个非常长的中文活动简介，需要被安全截断。" + "内容".repeat(300);

        var context = assembler.assemble(new ContextAssemblyRequest(
                2L, unifiedProfile(), longBrief, "目标", List.of(),
                List.of(CampaignChannel.BLOG), 200));

        assertThat(context.truncated()).isTrue();
        assertThat(context.renderedText().codePointCount(
                0, context.renderedText().length())).isLessThanOrEqualTo(200);
    }

    @Test
    void unifiedProfileReceivesWorkingMemoryEpisodicAndInsights() {
        memoryPort.workingMemory = new RunWorkingMemory(2L, "ARTIFACT", 1, 0, 5L, NOW,
                List.of(summary("memory-marker")), BudgetSnapshot.ZERO);
        memoryPort.episode = new CampaignEpisodicMemory(1L, 2L, List.of(), List.of(), List.of());
        memoryPort.insights = List.of(hit(3L, "INSIGHT-MARKER"));

        var context = assembler.assemble(request(null, List.of()));

        assertThat(context.renderedText())
                .contains("[WORKING_MEMORY]")
                .contains("memory-marker")
                .contains("[EPISODIC_SUMMARY]")
                .contains("[APPROVED_INSIGHTS]")
                .contains("INSIGHT-MARKER");
    }

    private ContextAssemblyRequest request(AgentRole role, List<AgentArtifact> artifacts) {
        return request(role, artifacts, List.of(CampaignChannel.BLOG));
    }

    private ContextAssemblyRequest request(AgentRole role, List<AgentArtifact> artifacts,
                                           List<CampaignChannel> channels) {
        AgentProfile profile = role == null ? unifiedProfile() : roleProfile(role);
        return new ContextAssemblyRequest(
                2L, profile, "objective=向开发者介绍 PulseInk; audience=Java 开发者; "
                + "channels=[BLOG]; constraints=[]",
                "创作一篇博客", artifacts, channels, 12_000);
    }

    private static AgentProfile unifiedProfile() {
        return AgentProfile.unified("unified", Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))));
    }

    private static AgentProfile roleProfile(AgentRole role) {
        return AgentProfile.role("role-" + role.name().toLowerCase(), role, Set.of(),
                new ModelPolicy(List.of("fake"), Set.of()),
                ExecutionBudget.defaultReact(Instant.now().plus(Duration.ofMinutes(30))),
                "system prompt", Set.of(ArtifactType.CONTENT_DRAFT), 1, 1, 1);
    }

    private static RunWorkingMemory.ArtifactSummary summary(String marker) {
        return new RunWorkingMemory.ArtifactSummary("artifact-1", "create-main",
                ArtifactType.CONTENT_DRAFT, 1, ArtifactStatus.VALID, marker);
    }

    private static ApprovedInsightHit hit(long id, String title) {
        return hit(id, title, InsightScopeType.WORKSPACE, "", List.of(CampaignChannel.BLOG));
    }

    private static ApprovedInsightHit hit(long id, String title, InsightScopeType scope,
                                          String scopeValue,
                                          List<CampaignChannel> channels) {
        return new ApprovedInsightHit(id, 7L, title, "正文", InsightCategory.STYLE_PREFERENCE,
                scope, scopeValue, channels, 0.8, NOW);
    }

    private static final class FakeMemoryPort implements MemoryPort {

        RunWorkingMemory workingMemory = RunWorkingMemory.empty(2L);
        CampaignEpisodicMemory episode = CampaignEpisodicMemory.empty(2L);
        List<ApprovedInsightHit> insights = List.of();
        Map<CampaignChannel, List<ApprovedInsightHit>> insightsByChannel =
                new java.util.EnumMap<>(CampaignChannel.class);
        List<CampaignChannel> searchedChannels = new ArrayList<>();
        int insightSearchCalls;

        @Override
        public WorkingMemoryResult loadRunWorkingMemory(long runId) {
            return new WorkingMemoryResult(workingMemory, false);
        }

        @Override
        public CampaignEpisodicMemory loadCampaignEpisode(long runId) {
            return episode;
        }

        @Override
        public List<ApprovedInsightHit> searchApprovedInsights(String query,
                                                               CampaignChannel channel,
                                                               int topK) {
            insightSearchCalls++;
            searchedChannels.add(channel);
            return new ArrayList<>(channel == null
                    ? insights : insightsByChannel.getOrDefault(channel, insights));
        }
    }
}
