package com.pulseink.client.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.artifact.ArtifactType;
import com.pulseink.agent.orchestration.AgentRole;
import java.util.Set;
import org.junit.jupiter.api.Test;

class YamlRoleProfileCatalogTest {

    private final YamlRoleProfileCatalog catalog = new YamlRoleProfileCatalog(
            "agent-profiles");

    @Test
    void loadsExactlyFiveRolesWithUniqueNames() {
        assertThat(catalog.allDefinitions()).hasSize(5);
        assertThat(catalog.allDefinitions())
                .extracting(d -> d.role())
                .containsExactlyInAnyOrder(
                        AgentRole.PLANNER, AgentRole.RESEARCHER, AgentRole.STRATEGIST,
                        AgentRole.CREATOR, AgentRole.REVIEWER);
        assertThat(catalog.allDefinitions()).extracting(d -> d.name())
                .doesNotHaveDuplicates();
    }

    @Test
    void promptsAreNonBlank() {
        assertThat(catalog.allDefinitions())
                .allSatisfy(d -> assertThat(d.systemPrompt()).isNotBlank());
    }

    @Test
    void toolAllowlistsExactlyMatchFixedPermissions() {
        assertThat(catalog.forRole(AgentRole.PLANNER).toolAllowlist()).isEmpty();
        assertThat(catalog.forRole(AgentRole.RESEARCHER).toolAllowlist())
                .containsExactly("builtin.knowledge_search");
        assertThat(catalog.forRole(AgentRole.STRATEGIST).toolAllowlist()).isEmpty();
        assertThat(catalog.forRole(AgentRole.CREATOR).toolAllowlist())
                .containsExactly("builtin.deterministic_validate");
        assertThat(catalog.forRole(AgentRole.REVIEWER).toolAllowlist())
                .containsExactly("builtin.deterministic_validate");
    }

    @Test
    void outputAllowlistsExactlyMatchFixedPermissions() {
        assertThat(catalog.forRole(AgentRole.PLANNER).allowedArtifactTypes()).isEmpty();
        assertThat(catalog.forRole(AgentRole.RESEARCHER).allowedArtifactTypes())
                .containsExactly(ArtifactType.EVIDENCE_PACK);
        assertThat(catalog.forRole(AgentRole.STRATEGIST).allowedArtifactTypes())
                .containsExactly(ArtifactType.CONTENT_STRATEGY);
        assertThat(catalog.forRole(AgentRole.CREATOR).allowedArtifactTypes())
                .containsExactly(ArtifactType.CONTENT_DRAFT);
        assertThat(catalog.forRole(AgentRole.REVIEWER).allowedArtifactTypes())
                .containsExactly(ArtifactType.REVIEW_REPORT);
    }

    @Test
    void localLimitsAreBounded() {
        assertThat(catalog.forRole(AgentRole.RESEARCHER).maxReactRounds()).isEqualTo(3);
        assertThat(catalog.forRole(AgentRole.RESEARCHER).maxToolCalls()).isEqualTo(1);
        assertThat(catalog.forRole(AgentRole.STRATEGIST).maxReactRounds()).isEqualTo(4);
        assertThat(catalog.forRole(AgentRole.CREATOR).maxReactRounds()).isEqualTo(4);
        assertThat(catalog.forRole(AgentRole.REVIEWER).maxReactRounds()).isEqualTo(4);
        assertThat(catalog.forRole(AgentRole.RESEARCHER).maxModelCalls()).isEqualTo(8);
        assertThat(catalog.forRole(AgentRole.PLANNER).maxModelCalls()).isEqualTo(3);
    }

    @Test
    void missingProfileDirectoryFails() {
        assertThatThrownBy(() -> new YamlRoleProfileCatalog("no-such-profiles"))
                .isInstanceOf(IllegalStateException.class);
    }
}
