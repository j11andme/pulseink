package com.pulseink.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.pulseink.agent.orchestration.AgentRole;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolPolicyTest {

    @Test
    void researcherCannotPublish() {
        var policy = ToolPolicy.forRole(
                AgentRole.RESEARCHER,
                Set.of("builtin.web_search", "builtin.knowledge_search"));
        var definition = ToolDefinition.of(
                "builtin", "publish_content", "desc",
                ToolDefinition.Schema.empty(), ToolRisk.EXTERNAL_SIDE_EFFECT);
        assertThatThrownBy(() -> policy.authorize(
                definition,
                ToolCall.of("builtin.publish_content", Map.of("contentId", 1L)),
                ApprovalState.NOT_APPROVED))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void externalSideEffectRequiresExplicitApproval() {
        var policy = ToolPolicy.forRole(
                AgentRole.CREATOR, Set.of("builtin.publish_content"));
        var definition = definition("builtin", "publish_content", ToolRisk.EXTERNAL_SIDE_EFFECT);
        var call = ToolCall.of("builtin.publish_content", Map.of());

        assertThatThrownBy(() -> policy.authorize(definition, call, ApprovalState.NOT_APPROVED))
                .isInstanceOf(ToolAuthorizationException.class);
        assertThatThrownBy(() -> policy.authorize(definition, call, ApprovalState.NOT_REQUIRED))
                .isInstanceOf(ToolAuthorizationException.class);
        assertThatCode(() -> policy.authorize(definition, call, ApprovalState.APPROVED))
                .doesNotThrowAnyException();
    }

    @Test
    void secretRiskAlsoRequiresApproval() {
        var policy = ToolPolicy.forRole(
                AgentRole.RESEARCHER, Set.of("builtin.credential_lookup"));
        var definition = definition("builtin", "credential_lookup", ToolRisk.SECRET);
        var call = ToolCall.of("builtin.credential_lookup", Map.of());
        assertThatThrownBy(() -> policy.authorize(definition, call, ApprovalState.NOT_REQUIRED))
                .isInstanceOf(ToolAuthorizationException.class);
        assertThatCode(() -> policy.authorize(definition, call, ApprovalState.APPROVED))
                .doesNotThrowAnyException();
    }

    @Test
    void readAndWriteRiskDoNotRequireApproval() {
        var policy = ToolPolicy.forRole(
                AgentRole.RESEARCHER, Set.of("builtin.search", "builtin.save_draft"));
        assertThatCode(() -> policy.authorize(
                definition("builtin", "search", ToolRisk.READ),
                ToolCall.of("builtin.search", Map.of()),
                ApprovalState.NOT_REQUIRED)).doesNotThrowAnyException();
        assertThatCode(() -> policy.authorize(
                definition("builtin", "save_draft", ToolRisk.WRITE),
                ToolCall.of("builtin.save_draft", Map.of()),
                ApprovalState.NOT_REQUIRED)).doesNotThrowAnyException();
    }

    @Test
    void toolNotInAllowlistRejected() {
        var policy = ToolPolicy.forRole(
                AgentRole.RESEARCHER, Set.of("builtin.search"));
        assertThatThrownBy(() -> policy.authorize(
                definition("builtin", "publish_content", ToolRisk.READ),
                ToolCall.of("builtin.publish_content", Map.of()),
                ApprovalState.NOT_REQUIRED))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void callQualifiedNameMustMatchDefinition() {
        var policy = ToolPolicy.forRole(
                AgentRole.RESEARCHER, Set.of("builtin.search"));
        assertThatThrownBy(() -> policy.authorize(
                definition("builtin", "search", ToolRisk.READ),
                ToolCall.of("builtin.other", Map.of()),
                ApprovalState.NOT_REQUIRED))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void schemaRequiredFieldMissingRejectedBeforeProvider() {
        var schema = ToolDefinition.Schema.of(
                Map.of("q", ToolDefinition.PropertySpec.of("string")),
                Set.of("q"), false);
        var definition = ToolDefinition.of("builtin", "search", "desc", schema, ToolRisk.READ);
        var policy = ToolPolicy.forRole(AgentRole.RESEARCHER, Set.of("builtin.search"));

        assertThatThrownBy(() -> policy.authorize(definition,
                ToolCall.of("builtin.search", Map.of()),
                ApprovalState.NOT_REQUIRED))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void schemaTypeMismatchRejectedBeforeProvider() {
        var schema = ToolDefinition.Schema.of(
                Map.of("q", ToolDefinition.PropertySpec.of("string")),
                Set.of(), false);
        var definition = ToolDefinition.of("builtin", "search", "desc", schema, ToolRisk.READ);
        var policy = ToolPolicy.forRole(AgentRole.RESEARCHER, Set.of("builtin.search"));

        assertThatThrownBy(() -> policy.authorize(definition,
                ToolCall.of("builtin.search", Map.of("q", 123)),
                ApprovalState.NOT_REQUIRED))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void schemaTypedPropertyRejectsNullValue() {
        var schema = ToolDefinition.Schema.of(
                Map.of("q", ToolDefinition.PropertySpec.of("string")),
                Set.of(), false);
        var definition = ToolDefinition.of("builtin", "search", "desc", schema, ToolRisk.READ);
        var policy = ToolPolicy.forRole(AgentRole.RESEARCHER, Set.of("builtin.search"));
        var arguments = new HashMap<String, Object>();
        arguments.put("q", null);

        assertThatThrownBy(() -> policy.authorize(
                definition, ToolCall.of("builtin.search", arguments), ApprovalState.NOT_REQUIRED))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void schemaAdditionalPropertyRejectedBeforeProvider() {
        var schema = ToolDefinition.Schema.of(
                Map.of("q", ToolDefinition.PropertySpec.of("string")),
                Set.of(), false);
        var definition = ToolDefinition.of("builtin", "search", "desc", schema, ToolRisk.READ);
        var policy = ToolPolicy.forRole(AgentRole.RESEARCHER, Set.of("builtin.search"));

        assertThatThrownBy(() -> policy.authorize(definition,
                ToolCall.of("builtin.search", Map.of("q", "ok", "extra", "bad")),
                ApprovalState.NOT_REQUIRED))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void schemaAdditionalPropertyAllowedWhenEnabled() {
        var schema = ToolDefinition.Schema.of(
                Map.of("q", ToolDefinition.PropertySpec.of("string")),
                Set.of(), true);
        var definition = ToolDefinition.of("builtin", "search", "desc", schema, ToolRisk.READ);
        var policy = ToolPolicy.forRole(AgentRole.RESEARCHER, Set.of("builtin.search"));

        assertThatCode(() -> policy.authorize(definition,
                ToolCall.of("builtin.search", Map.of("q", "ok", "extra", "ok")),
                ApprovalState.NOT_REQUIRED)).doesNotThrowAnyException();
    }

    @Test
    void nullZeroNegativeOrExcessiveTimeoutRejectedBeforeProvider() {
        var policy = ToolPolicy.forRole(AgentRole.RESEARCHER, Set.of("builtin.search"));

        assertThatThrownBy(() -> policy.validateTimeout(null))
                .isInstanceOf(ToolAuthorizationException.class);
        assertThatThrownBy(() -> policy.validateTimeout(Duration.ZERO))
                .isInstanceOf(ToolAuthorizationException.class);
        assertThatThrownBy(() -> policy.validateTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(ToolAuthorizationException.class);
        assertThatThrownBy(() -> policy.validateTimeout(Duration.ofHours(1)))
                .isInstanceOf(ToolAuthorizationException.class);
        assertThatCode(() -> policy.validateTimeout(Duration.ofSeconds(5)))
                .doesNotThrowAnyException();
    }

    @Test
    void customTimeoutLimitEnforced() {
        var policy = ToolPolicy.forRole(AgentRole.RESEARCHER, Set.of("builtin.search"))
                .withMaxTimeout(Duration.ofSeconds(10));
        assertThatCode(() -> policy.validateTimeout(Duration.ofSeconds(10)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validateTimeout(Duration.ofSeconds(11)))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void customPolicyLimitsMustBePositive() {
        var policy = ToolPolicy.forRole(AgentRole.RESEARCHER, Set.of("builtin.search"));

        assertThatThrownBy(() -> policy.withMaxTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.withMaxTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.withMaxResponseBytes(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.withMaxResponseBytes(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responseSizeLimitEnforcedAfterProvider() {
        var policy = ToolPolicy.forRole(AgentRole.RESEARCHER, Set.of("builtin.search"))
                .withMaxResponseBytes(10);

        assertThatCode(() -> policy.validateResponseSize(ToolResult.of("hello")))
                .doesNotThrowAnyException();

        var thrown = catchThrowable(() ->
                policy.validateResponseSize(ToolResult.of("this is way too long for 10 bytes")));
        assertThat(thrown).isInstanceOf(ToolInvocationException.class);
    }

    @Test
    void forProfileUsesProfileAllowlist() {
        var profile = com.pulseink.agent.orchestration.AgentProfile.of(
                "researcher", AgentRole.RESEARCHER, Set.of("builtin.search"));
        var policy = ToolPolicy.forProfile(profile);
        assertThatCode(() -> policy.authorize(
                definition("builtin", "search", ToolRisk.READ),
                ToolCall.of("builtin.search", Map.of()),
                ApprovalState.NOT_REQUIRED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.authorize(
                definition("builtin", "other", ToolRisk.READ),
                ToolCall.of("builtin.other", Map.of()),
                ApprovalState.NOT_REQUIRED))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void forProfileWorksWithUnifiedProfileWithoutCallingSingleRole() {
        var profile = com.pulseink.agent.orchestration.AgentProfile.unified(
                "unified",
                Set.of("builtin.search"),
                new com.pulseink.agent.model.ModelPolicy(
                        java.util.List.of("fake"), Set.of()),
                com.pulseink.agent.budget.ExecutionBudget.defaultReact(
                        java.time.Instant.now().plus(java.time.Duration.ofMinutes(30))));
        var policy = ToolPolicy.forProfile(profile);
        assertThatCode(() -> policy.authorize(
                definition("builtin", "search", ToolRisk.READ),
                ToolCall.of("builtin.search", Map.of()),
                ApprovalState.NOT_REQUIRED)).doesNotThrowAnyException();
    }

    private static ToolDefinition definition(String namespace, String localName, ToolRisk risk) {
        return ToolDefinition.of(namespace, localName, "desc", ToolDefinition.Schema.empty(), risk);
    }
}
