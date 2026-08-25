package com.pulseink.client.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.plan.PlanParser;
import com.pulseink.agent.plan.PlanSpec;
import com.pulseink.agent.plan.PlanTask;
import com.pulseink.agent.plan.PlanTaskAccess;
import org.junit.jupiter.api.Test;

class JacksonPlanParserTest {

    private final PlanParser parser = new JacksonPlanParser();

    @Test
    void parsesMinimalTwoTaskPlan() {
        var plan = parser.parse("""
                {
                  "schemaVersion": 1,
                  "tasks": [
                    {
                      "taskId": "strategy-main",
                      "role": "STRATEGIST",
                      "objective": "基于证据形成内容策略",
                      "dependsOn": [],
                      "requiredArtifactTypes": ["EVIDENCE_PACK"],
                      "outputArtifactType": "CONTENT_STRATEGY",
                      "access": "READ_ONLY"
                    },
                    {
                      "taskId": "create-blog",
                      "role": "CREATOR",
                      "objective": "创作博客内容",
                      "dependsOn": ["strategy-main"],
                      "requiredArtifactTypes": [],
                      "outputArtifactType": "CONTENT_DRAFT",
                      "access": "READ_ONLY"
                    }
                  ]
                }
                """);

        assertThat(plan.schemaVersion()).isEqualTo(1);
        assertThat(plan.tasks()).hasSize(2);
        var strategy = plan.tasks().get(0);
        assertThat(strategy.taskId()).isEqualTo("strategy-main");
        assertThat(strategy.role()).isEqualTo(AgentRole.STRATEGIST);
        assertThat(strategy.requiredArtifactTypes()).containsExactly(ArtifactType.EVIDENCE_PACK);
        assertThat(strategy.outputArtifactType()).isEqualTo(ArtifactType.CONTENT_STRATEGY);
        assertThat(strategy.access()).isEqualTo(PlanTaskAccess.READ_ONLY);
        var creator = plan.tasks().get(1);
        assertThat(creator.dependsOn()).containsExactly("strategy-main");
    }

    @Test
    void rejectsUnknownFields() {
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"sneaky":true,"tasks":[
                  {"taskId":"a","role":"STRATEGIST","objective":"o","dependsOn":[],
                   "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_STRATEGY",
                   "access":"READ_ONLY"}]}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"tasks":[
                  {"taskId":"a","role":"STRATEGIST","objective":"o","dependsOn":[],
                   "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_STRATEGY",
                   "access":"READ_ONLY","extra":1}]}
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownRoleAccessAndOutput() {
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"tasks":[
                  {"taskId":"a","role":"UNKNOWN","objective":"o","dependsOn":[],
                   "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_STRATEGY",
                   "access":"READ_ONLY"}]}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"tasks":[
                  {"taskId":"a","role":"STRATEGIST","objective":"o","dependsOn":[],
                   "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_STRATEGY",
                   "access":"WRITE"}]}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"tasks":[
                  {"taskId":"a","role":"STRATEGIST","objective":"o","dependsOn":[],
                   "requiredArtifactTypes":[],"outputArtifactType":"NOT_A_TYPE",
                   "access":"READ_ONLY"}]}
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingFieldsAndTypedNull() {
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"tasks":[
                  {"taskId":"a","role":"STRATEGIST","dependsOn":[],
                   "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_STRATEGY",
                   "access":"READ_ONLY"}]}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"tasks":[{"taskId":null,"role":"STRATEGIST","objective":"o",
                   "dependsOn":[],"requiredArtifactTypes":[],"outputArtifactType":"CONTENT_STRATEGY",
                   "access":"READ_ONLY"}]}
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankObjectiveAndMalformedJson() {
        assertThatThrownBy(() -> parser.parse("""
                {"schemaVersion":1,"tasks":[
                  {"taskId":"a","role":"STRATEGIST","objective":"  ","dependsOn":[],
                   "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_STRATEGY",
                   "access":"READ_ONLY"}]}
                """))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("{not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsFirstBalancedObjectFromModelCommentary() {
        var plan = parser.parse("""
                Here is the plan:
                {"schemaVersion":1,"tasks":[
                  {"taskId":"s","role":"STRATEGIST","objective":"use {brief}",
                   "dependsOn":[],"requiredArtifactTypes":[],
                   "outputArtifactType":"CONTENT_STRATEGY","access":"READ_ONLY"},
                  {"taskId":"c","role":"CREATOR","objective":"draft","dependsOn":["s"],
                   "requiredArtifactTypes":[],"outputArtifactType":"CONTENT_DRAFT",
                   "access":"READ_ONLY"}]}
                Additional note: {do not parse this}.
                """);

        assertThat(plan.tasks()).hasSize(2);
        assertThat(plan.tasks().get(0).objective()).isEqualTo("use {brief}");
    }
}
