package com.pulseink.service.evaluation;

import com.pulseink.domain.campaign.CampaignChannel;
import com.pulseink.domain.execution.TaskProperties;
import com.pulseink.domain.execution.ExecutionPolicy;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** A versioned, immutable PulseInk-Eval case. */
public record EvaluationCase(
        String caseId,
        String category,
        boolean smoke,
        TaskProperties taskProperties,
        CampaignInput campaignInput,
        String knowledgeSnapshot,
        String searchFixtures,
        List<String> expectedRules,
        List<String> relevantChunkIds,
        Set<String> allowedTools,
        String expectedFinalState,
        String rubric,
        List<String> failureInjection,
        Set<ExecutionPolicy> applicablePolicies) {

    private static final Pattern CASE_ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    public EvaluationCase {
        if (caseId == null || !CASE_ID.matcher(caseId).matches()) {
            throw new IllegalArgumentException("caseId must be kebab-case");
        }
        category = requireText(category, "category");
        taskProperties = Objects.requireNonNull(taskProperties, "taskProperties must not be null");
        campaignInput = Objects.requireNonNull(campaignInput, "campaignInput must not be null");
        knowledgeSnapshot = requireText(knowledgeSnapshot, "knowledgeSnapshot");
        searchFixtures = requireText(searchFixtures, "searchFixtures");
        expectedRules = copyTexts(expectedRules, "expectedRules");
        relevantChunkIds = copyTexts(relevantChunkIds, "relevantChunkIds");
        allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        expectedFinalState = requireText(expectedFinalState, "expectedFinalState");
        rubric = requireText(rubric, "rubric");
        failureInjection = failureInjection == null ? List.of() : List.copyOf(failureInjection);
        applicablePolicies = applicablePolicies == null || applicablePolicies.isEmpty()
                ? Set.copyOf(EnumSet.allOf(ExecutionPolicy.class))
                : Set.copyOf(applicablePolicies);
    }

    public EvaluationCase(
            String caseId, String category, boolean smoke, TaskProperties taskProperties,
            CampaignInput campaignInput, String knowledgeSnapshot, String searchFixtures,
            List<String> expectedRules, List<String> relevantChunkIds, Set<String> allowedTools,
            String expectedFinalState, String rubric, List<String> failureInjection) {
        this(caseId, category, smoke, taskProperties, campaignInput, knowledgeSnapshot,
                searchFixtures, expectedRules, relevantChunkIds, allowedTools,
                expectedFinalState, rubric, failureInjection, null);
    }

    private static List<String> copyTexts(List<String> values, String name) {
        if (values == null) {
            return List.of();
        }
        var copy = List.copyOf(values);
        if (copy.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return copy;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record CampaignInput(
            String name,
            String goal,
            String audience,
            List<CampaignChannel> channels,
            List<String> constraints) {
        public CampaignInput {
            name = requireText(name, "campaignInput.name");
            goal = requireText(goal, "campaignInput.goal");
            audience = requireText(audience, "campaignInput.audience");
            channels = channels == null ? List.of() : List.copyOf(channels);
            if (channels.isEmpty()) {
                throw new IllegalArgumentException("campaignInput.channels must not be empty");
            }
            constraints = copyTexts(constraints, "campaignInput.constraints");
        }
    }
}
