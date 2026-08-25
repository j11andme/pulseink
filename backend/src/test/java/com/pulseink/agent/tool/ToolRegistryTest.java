package com.pulseink.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.AgentRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    // ---- namespace collision and duplicate detection ----

    @Test
    void namesAreCollisionFreeAcrossProviders() {
        var registry = new ToolRegistry(List.of(
                fakeProvider("builtin", "search"),
                fakeProvider("mcp.docs", "search")));
        assertThat(registry.names())
                .containsExactlyInAnyOrder("builtin.search", "mcp.docs.search");
    }

    @Test
    void duplicateNamespaceRejected() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                fakeProvider("builtin", "search"),
                fakeProvider("builtin", "fetch"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateQualifiedNameWithinProviderRejected() {
        var provider = new ToolProvider() {
            private final List<ToolDefinition> defs = List.of(
                    ToolDefinition.of("builtin", "search", "d",
                            ToolDefinition.Schema.empty(), ToolRisk.READ),
                    ToolDefinition.of("builtin", "search", "d",
                            ToolDefinition.Schema.empty(), ToolRisk.READ));

            @Override
            public String namespace() {
                return "builtin";
            }

            @Override
            public List<ToolDefinition> discover() {
                return defs;
            }

            @Override
            public ToolResult invoke(ToolCall call, Duration timeout) {
                return ToolResult.of("ok");
            }
        };
        assertThatThrownBy(() -> new ToolRegistry(List.of(provider)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unhealthyProviderAtConstructionHasNoTools() {
        var provider = new ToolProvider() {
            @Override
            public String namespace() {
                return "mcp.broken";
            }

            @Override
            public List<ToolDefinition> discover() {
                throw new RuntimeException("connection refused");
            }

            @Override
            public ToolResult invoke(ToolCall call, Duration timeout) {
                return ToolResult.of("ok");
            }
        };
        var registry = new ToolRegistry(List.of(
                fakeProvider("builtin", "search"),
                provider));
        assertThat(registry.names()).containsExactly("builtin.search");
    }

    @Test
    void refreshRejectsDefinitionWhoseNamespaceChanged() {
        var provider = new ToolProvider() {
            private int discoveries;

            @Override
            public String namespace() {
                return "mcp.docs";
            }

            @Override
            public List<ToolDefinition> discover() {
                discoveries++;
                String namespace = discoveries == 1 ? "mcp.docs" : "mcp.other";
                return List.of(ToolDefinition.of(
                        namespace, "search", "desc",
                        ToolDefinition.Schema.empty(), ToolRisk.READ));
            }

            @Override
            public ToolResult invoke(ToolCall call, Duration timeout) {
                return ToolResult.of("ok");
            }
        };
        var registry = new ToolRegistry(List.of(provider));

        assertThatThrownBy(registry::refresh)
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- schemasFor filtering ----

    @Test
    void schemasForReturnsOnlyAllowedEnabledHealthyTools() {
        var registry = new ToolRegistry(List.of(
                fakeProvider("builtin", "search", "publish_content", "fetch_page")));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.search", "builtin.publish_content"));

        var schemas = registry.schemasFor(profile);
        assertThat(schemas).extracting(ToolDefinition::qualifiedName)
                .containsExactlyInAnyOrder("builtin.search", "builtin.publish_content");
    }

    @Test
    void schemasForExcludesNotAllowedlist() {
        var registry = new ToolRegistry(List.of(
                fakeProvider("builtin", "search", "publish_content")));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER, Set.of("builtin.search"));

        assertThat(registry.schemasFor(profile)).extracting(ToolDefinition::qualifiedName)
                .containsExactly("builtin.search");
    }

    @Test
    void schemasForExcludesDisabledTools() {
        var provider = fakeProvider("builtin", "search", "publish_content");
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var registry = new ToolRegistry(List.of(provider), clock,
                Set.of("builtin.publish_content"));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.search", "builtin.publish_content"));

        assertThat(registry.schemasFor(profile)).extracting(ToolDefinition::qualifiedName)
                .containsExactly("builtin.search");
    }

    @Test
    void schemasForExcludesUnhealthyToolsAfterRefresh() {
        var provider = new FlakyProvider("mcp.docs", "search");
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var registry = new ToolRegistry(List.of(provider), clock, Set.of());
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("mcp.docs.search"));

        assertThat(registry.schemasFor(profile)).extracting(ToolDefinition::qualifiedName)
                .containsExactly("mcp.docs.search");

        var refreshed = registry.refresh();
        assertThat(refreshed.schemasFor(profile)).isEmpty();
    }

    @Test
    void schemasForReturnsImmutableList() {
        var registry = new ToolRegistry(List.of(fakeProvider("builtin", "search")));
        var profile = AgentProfile.of("r", AgentRole.RESEARCHER, Set.of("builtin.search"));
        var schemas = registry.schemasFor(profile);
        assertThatThrownBy(() -> schemas.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- invokeAuthorized ----

    @Test
    void unauthorizedSideEffectNeverReachesProvider() {
        var counter = new CountingProvider("builtin", "publish_content",
                ToolRisk.EXTERNAL_SIDE_EFFECT);
        var registry = new ToolRegistry(List.of(counter));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.publish_content"));

        assertThatThrownBy(() -> registry.invokeAuthorized(profile,
                ToolCall.of("builtin.publish_content", Map.of()),
                ApprovalState.NOT_APPROVED,
                Duration.ofSeconds(5)))
                .isInstanceOf(ToolAuthorizationException.class);
        assertThat(counter.invokeCount).isEqualTo(0);
    }

    @Test
    void toolNotInAllowlistRejectedByRegistry() {
        var registry = new ToolRegistry(List.of(
                fakeProvider("builtin", "search", "publish_content")));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.search"));

        assertThatThrownBy(() -> registry.invokeAuthorized(profile,
                ToolCall.of("builtin.publish_content", Map.of()),
                ApprovalState.NOT_REQUIRED,
                Duration.ofSeconds(5)))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void disabledToolRejectedByRegistry() {
        var provider = fakeProvider("builtin", "search", "publish_content");
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var registry = new ToolRegistry(List.of(provider), clock,
                Set.of("builtin.publish_content"));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.search", "builtin.publish_content"));

        assertThatThrownBy(() -> registry.invokeAuthorized(profile,
                ToolCall.of("builtin.publish_content", Map.of()),
                ApprovalState.NOT_REQUIRED,
                Duration.ofSeconds(5)))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void unhealthyToolRejectedByRegistry() {
        var provider = new FlakyProvider("mcp.docs", "search");
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var registry = new ToolRegistry(List.of(provider), clock, Set.of()).refresh();
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("mcp.docs.search"));

        assertThatThrownBy(() -> registry.invokeAuthorized(profile,
                ToolCall.of("mcp.docs.search", Map.of()),
                ApprovalState.NOT_REQUIRED,
                Duration.ofSeconds(5)))
                .isInstanceOf(ToolInvocationException.class);
    }

    @Test
    void providerReceivesAdjudicatedTimeout() {
        var counter = new CountingProvider("builtin", "search", ToolRisk.READ);
        var registry = new ToolRegistry(List.of(counter));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.search"));

        registry.invokeAuthorized(profile,
                ToolCall.of("builtin.search", Map.of()),
                ApprovalState.NOT_REQUIRED,
                Duration.ofSeconds(7));

        assertThat(counter.invokeCount).isEqualTo(1);
        assertThat(counter.receivedTimeout).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void validCallReturnsResult() {
        var registry = new ToolRegistry(List.of(fakeProvider("builtin", "search")));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.search"));

        var result = registry.invokeAuthorized(profile,
                ToolCall.of("builtin.search", Map.of()),
                ApprovalState.NOT_REQUIRED,
                Duration.ofSeconds(5));
        assertThat(result.contentText()).isEqualTo("ok");
    }

    @Test
    void unregisteredToolRejected() {
        var registry = new ToolRegistry(List.of(fakeProvider("builtin", "search")));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER, Set.of());

        assertThatThrownBy(() -> registry.invokeAuthorized(profile,
                ToolCall.of("builtin.unknown", Map.of()),
                ApprovalState.NOT_REQUIRED,
                Duration.ofSeconds(5)))
                .isInstanceOf(ToolAuthorizationException.class);
    }

    @Test
    void invalidTimeoutRejectedBeforeProvider() {
        var counter = new CountingProvider("builtin", "search", ToolRisk.READ);
        var registry = new ToolRegistry(List.of(counter));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.search"));

        assertThatThrownBy(() -> registry.invokeAuthorized(profile,
                ToolCall.of("builtin.search", Map.of()),
                ApprovalState.NOT_REQUIRED,
                Duration.ZERO))
                .isInstanceOf(ToolAuthorizationException.class);
        assertThat(counter.invokeCount).isEqualTo(0);
    }

    @Test
    void providerExceptionNormalizedAndRedacted() {
        var provider = new ThrowingProvider("builtin", "search",
                new RuntimeException(
                        "Authorization: Bearer sk-secret-token, password=hunter2\n"
                                + "at com.pulseink.internal.SecretClass.method(SecretClass.java:42)"));
        var registry = new ToolRegistry(List.of(provider));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.search"));

        var thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                registry.invokeAuthorized(profile,
                        ToolCall.of("builtin.search", Map.of()),
                        ApprovalState.NOT_REQUIRED,
                        Duration.ofSeconds(5)));

        assertThat(thrown).isInstanceOf(ToolInvocationException.class);
        assertThat(thrown.getMessage())
                .doesNotContain("Bearer")
                .doesNotContain("sk-secret-token")
                .doesNotContain("hunter2")
                .doesNotContain("Authorization")
                .doesNotContain("SecretClass")
                .doesNotContain(".java:");
    }

    @Test
    void oversizedResponseRejectedAfterProvider() {
        var provider = new ToolProvider() {
            @Override
            public String namespace() {
                return "builtin";
            }

            @Override
            public List<ToolDefinition> discover() {
                return List.of(ToolDefinition.of("builtin", "search", "d",
                        ToolDefinition.Schema.empty(), ToolRisk.READ));
            }

            @Override
            public ToolResult invoke(ToolCall call, Duration timeout) {
                return ToolResult.of("x".repeat(2_000_000));
            }
        };
        var registry = new ToolRegistry(List.of(provider));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER,
                Set.of("builtin.search"));

        assertThatThrownBy(() -> registry.invokeAuthorized(profile,
                ToolCall.of("builtin.search", Map.of()),
                ApprovalState.NOT_REQUIRED,
                Duration.ofSeconds(5)))
                .isInstanceOf(ToolInvocationException.class);
    }

    @Test
    void definitionSnapshotIsSortedImmutableAndCarriesOnlyPublicMetadata() {
        var registry = new ToolRegistry(List.of(
                fakeProvider("builtin", "search", "fetch_page"),
                fakeProvider("mcp.docs", "search")));

        var snapshot = registry.definitionSnapshot();

        assertThat(snapshot).extracting(ToolDefinition::qualifiedName)
                .containsExactly(
                        "builtin.fetch_page",
                        "builtin.search",
                        "mcp.docs.search");
        assertThat(snapshot).extracting(ToolDefinition::description)
                .allMatch(description -> description.equals("desc"));
        assertThat(snapshot).extracting(ToolDefinition::risk)
                .allMatch(risk -> risk == ToolRisk.READ);
        assertThatThrownBy(() -> snapshot.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- helpers ----

    private static ToolProvider fakeProvider(String namespace, String... localNames) {
        var defs = Arrays.stream(localNames)
                .map(ln -> ToolDefinition.of(namespace, ln, "desc",
                        ToolDefinition.Schema.empty(), ToolRisk.READ))
                .toList();
        return new ToolProvider() {
            @Override
            public String namespace() {
                return namespace;
            }

            @Override
            public List<ToolDefinition> discover() {
                return defs;
            }

            @Override
            public ToolResult invoke(ToolCall call, Duration timeout) {
                return ToolResult.of("ok");
            }
        };
    }

    static final class CountingProvider implements ToolProvider {
        private final String namespace;
        private final List<ToolDefinition> defs;
        int invokeCount = 0;
        Duration receivedTimeout;

        CountingProvider(String namespace, String localName, ToolRisk risk) {
            this.namespace = namespace;
            this.defs = List.of(ToolDefinition.of(namespace, localName, "desc",
                    ToolDefinition.Schema.empty(), risk));
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public List<ToolDefinition> discover() {
            return defs;
        }

        @Override
        public ToolResult invoke(ToolCall call, Duration timeout) {
            invokeCount++;
            receivedTimeout = timeout;
            return ToolResult.of("ok");
        }
    }

    static final class FlakyProvider implements ToolProvider {
        private final String namespace;
        private final List<ToolDefinition> defs;
        private int discoverCalls = 0;

        FlakyProvider(String namespace, String localName) {
            this.namespace = namespace;
            this.defs = List.of(ToolDefinition.of(namespace, localName, "desc",
                    ToolDefinition.Schema.empty(), ToolRisk.READ));
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public List<ToolDefinition> discover() {
            discoverCalls++;
            if (discoverCalls > 1) {
                throw new RuntimeException("connection lost");
            }
            return defs;
        }

        @Override
        public ToolResult invoke(ToolCall call, Duration timeout) {
            return ToolResult.of("ok");
        }
    }

    static final class ThrowingProvider implements ToolProvider {
        private final String namespace;
        private final List<ToolDefinition> defs;
        private final RuntimeException toThrow;

        ThrowingProvider(String namespace, String localName, RuntimeException toThrow) {
            this.namespace = namespace;
            this.defs = List.of(ToolDefinition.of(namespace, localName, "desc",
                    ToolDefinition.Schema.empty(), ToolRisk.READ));
            this.toThrow = toThrow;
        }

        @Override
        public String namespace() {
            return namespace;
        }

        @Override
        public List<ToolDefinition> discover() {
            return defs;
        }

        @Override
        public ToolResult invoke(ToolCall call, Duration timeout) {
            throw toThrow;
        }
    }
}
