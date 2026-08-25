package com.pulseink.client.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.pulseink.agent.orchestration.AgentProfile;
import com.pulseink.agent.orchestration.AgentRole;
import com.pulseink.agent.tool.ApprovalState;
import com.pulseink.agent.tool.ToolCall;
import com.pulseink.agent.tool.ToolDefinition;
import com.pulseink.agent.tool.ToolInvocationException;
import com.pulseink.agent.tool.ToolProvider;
import com.pulseink.agent.tool.ToolRegistry;
import com.pulseink.agent.tool.ToolResult;
import com.pulseink.agent.tool.ToolRisk;
import com.pulseink.client.mcp.StdioMcpToolProvider;
import com.pulseink.client.mcp.StreamableHttpMcpToolProvider;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract tests for the governed tool boundary. They exercise the immutable Tool contracts,
 * namespace validation and the {@link ToolProvider} SPI shape without a model, network or process.
 */
class ToolProviderContractTest {

    // ---- namespace / local name / qualified name validation ----

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", ".builtin", "builtin.", "built..in", "built!in", "built in",
            ".a", "a.b.", "名前", "a.b.c."})
    void invalidNamespaceRejected(String namespace) {
        assertThatThrownBy(() -> ToolDefinition.of(
                namespace, "tool", "desc", ToolDefinition.Schema.empty(), ToolRisk.READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"search", "web_search", "fetch-page", "mcp.docs", "MCP_DOCS", "a.b.c"})
    void validNamespaceAccepted(String namespace) {
        assertThatCode(() -> ToolDefinition.of(
                namespace, "tool", "desc", ToolDefinition.Schema.empty(), ToolRisk.READ))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", ".t", "t.", "t..s", "t s", "t!", "tool."})
    void invalidLocalNameRejected(String localName) {
        assertThatThrownBy(() -> ToolDefinition.of(
                "builtin", localName, "desc", ToolDefinition.Schema.empty(), ToolRisk.READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", ".x", "x.", "x..y", "x y", "x!", "a.b."})
    void invalidQualifiedNameRejectedOnToolCall(String qualifiedName) {
        assertThatThrownBy(() -> ToolCall.of(qualifiedName, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void qualifiedNameJoinsNamespaceAndLocalName() {
        var definition = ToolDefinition.of(
                "mcp.docs", "search", "desc", ToolDefinition.Schema.empty(), ToolRisk.READ);
        assertThat(definition.namespace()).isEqualTo("mcp.docs");
        assertThat(definition.name()).isEqualTo("search");
        assertThat(definition.qualifiedName()).isEqualTo("mcp.docs.search");
    }

    @Test
    void toolDefinitionRejectsNullMandatoryFields() {
        assertThatThrownBy(() -> ToolDefinition.of(
                "builtin", "t", "d", null, ToolRisk.READ))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolDefinition.of(
                "builtin", "t", "d", ToolDefinition.Schema.empty(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ToolDefinition.of(
                "builtin", "t", "  ", ToolDefinition.Schema.empty(), ToolRisk.READ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireValidNamespaceReturnsNormalizedValueAndRejectsBadInput() {
        assertThat(ToolDefinition.requireValidNamespace("mcp.docs")).isEqualTo("mcp.docs");
        assertThatThrownBy(() -> ToolDefinition.requireValidNamespace("bad ns"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- defensive / deep-immutable copies ----

    @Test
    void toolCallDefendsMutableArguments() {
        var nested = new HashMap<String, Object>();
        nested.put("inner", "v");
        var list = new ArrayList<>(List.of(1, 2));
        var args = new HashMap<String, Object>();
        args.put("list", list);
        args.put("obj", nested);

        var call = ToolCall.of("builtin.search", args);

        list.add(3);
        nested.put("extra", "x");
        args.put("sneaky", "y");

        assertThat(call.arguments()).hasSize(2).doesNotContainKey("sneaky");

        @SuppressWarnings("unchecked")
        List<Object> listView = (List<Object>) call.arguments().get("list");
        assertThat(listView).containsExactly(1, 2);
        assertThatThrownBy(() -> listView.add(9))
                .isInstanceOf(UnsupportedOperationException.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> objView = (Map<String, Object>) call.arguments().get("obj");
        assertThat(objView).hasSize(1);
        assertThatThrownBy(() -> objView.put("z", 0))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> call.arguments().put("z", 0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void toolCallRejectsNullArgumentsAndTooManyArguments() {
        assertThatThrownBy(() -> ToolCall.of("builtin.search", null))
                .isInstanceOf(IllegalArgumentException.class);
        var args = new HashMap<String, Object>();
        for (int i = 0; i < ToolCall.MAX_ARGUMENT_COUNT + 1; i++) {
            args.put("k" + i, i);
        }
        assertThatThrownBy(() -> ToolCall.of("builtin.search", args))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolResultDefendsMutableInputs() {
        var bytes = "hello".getBytes(StandardCharsets.UTF_8);
        var metadata = new HashMap<String, String>();
        metadata.put("k", "v");

        var result = ToolResult.of(bytes, metadata);

        bytes[0] = 'X';
        metadata.put("extra", "y");

        assertThat(result.contentText()).isEqualTo("hello");
        assertThat(result.content()).doesNotContain((byte) 'X');
        assertThat(result.metadata()).containsOnly(entry("k", "v"));
        assertThatThrownBy(() -> result.metadata().put("z", "w"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void schemaDefendsMutableInputs() {
        var properties = new HashMap<String, ToolDefinition.PropertySpec>();
        properties.put("q", ToolDefinition.PropertySpec.of("string"));
        var required = new HashSet<>(Set.of("q"));

        var schema = ToolDefinition.Schema.of(properties, required, false);

        properties.put("extra", ToolDefinition.PropertySpec.of("integer"));
        required.add("z");

        assertThat(schema.properties()).containsOnlyKeys("q");
        assertThat(schema.required()).containsExactly("q");
        assertThat(schema.additionalProperties()).isFalse();
        assertThatThrownBy(() -> schema.properties().put("z", null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> schema.required().add("z"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void schemaRejectsRequiredKeyWithoutProperty() {
        assertThatThrownBy(() -> ToolDefinition.Schema.of(
                Map.of("q", ToolDefinition.PropertySpec.of("string")),
                Set.of("missing"), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "date", "null", "callable"})
    void propertySpecRejectsUnsupportedJsonSchemaType(String type) {
        assertThatThrownBy(() -> ToolDefinition.PropertySpec.of(type))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentProfileIsImmutable() {
        var allow = new HashSet<>(Set.of("builtin.search"));
        var profile = AgentProfile.of("researcher", AgentRole.RESEARCHER, allow);

        allow.add("builtin.publish_content");

        assertThat(profile.name()).isEqualTo("researcher");
        assertThat(profile.role()).isEqualTo(AgentRole.RESEARCHER);
        assertThat(profile.allowedTools()).containsExactly("builtin.search");
        assertThatThrownBy(() -> profile.allowedTools().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void agentProfileRejectsBlankNameAndNullRole() {
        assertThatThrownBy(() -> AgentProfile.of("  ", AgentRole.RESEARCHER, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentProfile.of("p", null, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AgentProfile.of("p", AgentRole.RESEARCHER, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void agentRoleHasFiveLogicalRoles() {
        assertThat(EnumSet.allOf(AgentRole.class)).containsExactlyInAnyOrder(
                AgentRole.PLANNER, AgentRole.RESEARCHER, AgentRole.STRATEGIST,
                AgentRole.CREATOR, AgentRole.REVIEWER);
    }

    // ---- no credential-like members on the public contract ----

    private static final List<Class<?>> CONTRACT_TYPES = List.of(
            ToolDefinition.class,
            ToolCall.class,
            ToolResult.class,
            ToolProvider.class,
            AgentProfile.class);

    @Test
    void noCredentialLikeMemberOnPublicContract() {
        for (Class<?> type : CONTRACT_TYPES) {
            for (Method method : type.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String name = method.getName().toLowerCase();
                assertThat(name)
                        .as(type.getSimpleName() + "." + method.getName())
                        .doesNotContain("token")
                        .doesNotContain("credential")
                        .doesNotContain("password")
                        .doesNotContain("secret")
                        .doesNotContain("apikey")
                        .doesNotContain("authorization");
            }
        }
    }

    // ---- ToolProvider SPI shape (usable without a model) ----

    @Test
    void spiShapeIsUsableWithoutModel() {
        var provider = new FakeToolProvider();

        assertThat(provider.namespace()).isEqualTo("builtin");
        assertThat(provider.discover())
                .hasSize(1)
                .extracting(ToolDefinition::qualifiedName)
                .containsExactly("builtin.search");

        var result = provider.invoke(
                ToolCall.of("builtin.search", Map.of("q", "x")), Duration.ofSeconds(5));
        assertThat(result.contentText()).isEqualTo("ok");
    }

    @Test
    void definitionNamespaceMatchesProviderNamespace() {
        var provider = new FakeToolProvider();
        assertThat(provider.discover())
                .allSatisfy(d -> assertThat(d.namespace()).isEqualTo(provider.namespace()));
    }

    // ---- JavaToolProvider ----

    @Test
    void javaToolProviderDiscoversRegisteredHandlers() {
        var provider = new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "search", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        (call, timeout) -> ToolResult.of("search-result")),
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "fetch", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        (call, timeout) -> ToolResult.of("fetch-result"))));

        assertThat(provider.namespace()).isEqualTo("builtin");
        assertThat(provider.discover()).extracting(ToolDefinition::qualifiedName)
                .containsExactlyInAnyOrder("builtin.search", "builtin.fetch");
    }

    @Test
    void javaToolProviderInvokesDeterministically() {
        var provider = new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "echo", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        (call, timeout) -> ToolResult.of(
                                "echo:" + call.arguments().get("msg")))));

        var result = provider.invoke(
                ToolCall.of("builtin.echo", Map.of("msg", "hello")),
                Duration.ofSeconds(5));
        assertThat(result.contentText()).isEqualTo("echo:hello");
    }

    @Test
    void javaToolProviderThrowsForUnknownTool() {
        var provider = new JavaToolProvider("builtin", List.of());
        assertThatThrownBy(() -> provider.invoke(
                ToolCall.of("builtin.unknown", Map.of()),
                Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void javaToolProviderPropagatesHandlerException() {
        var provider = new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "fail", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        (call, timeout) -> { throw new RuntimeException("handler exploded"); })));
        assertThatThrownBy(() -> provider.invoke(
                ToolCall.of("builtin.fail", Map.of()),
                Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("handler exploded");
    }

    @Test
    void javaToolProviderRejectsNamespaceMismatch() {
        assertThatThrownBy(() -> new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of("other", "search", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        (call, timeout) -> ToolResult.of("ok")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void javaToolProviderHandlerExceptionNormalizedByRegistry() {
        var provider = new JavaToolProvider("builtin", List.of(
                new JavaToolProvider.Registration(
                        ToolDefinition.of("builtin", "fail", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        (call, timeout) -> {
                            throw new RuntimeException("password=secret123 token=abc");
                        })));
        var registry = new ToolRegistry(List.of(provider));
        var profile = AgentProfile.of("r", AgentRole.RESEARCHER, Set.of("builtin.fail"));

        assertThatThrownBy(() -> registry.invokeAuthorized(profile,
                ToolCall.of("builtin.fail", Map.of()),
                ApprovalState.NOT_REQUIRED,
                Duration.ofSeconds(5)))
                .isInstanceOf(ToolInvocationException.class)
                .satisfies(t -> assertThat(t.getMessage())
                        .doesNotContain("password")
                        .doesNotContain("secret123")
                        .doesNotContain("token")
                        .doesNotContain("abc"));
    }

    // ---- HttpToolProvider ----

    @Test
    void httpToolProviderDiscoversConfiguredSchemas() {
        var provider = new HttpToolProvider("http", "https://api.example.com",
                List.of(
                        new HttpToolProvider.Registration(
                                ToolDefinition.of("http", "search", "d",
                                        ToolDefinition.Schema.empty(), ToolRisk.READ),
                                "GET", "/search"),
                        new HttpToolProvider.Registration(
                                ToolDefinition.of("http", "fetch", "d",
                                        ToolDefinition.Schema.empty(), ToolRisk.READ),
                                "GET", "/fetch")),
                (req, timeout) -> new HttpToolProvider.HttpResponse(
                        200, "ok".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThat(provider.namespace()).isEqualTo("http");
        assertThat(provider.discover()).extracting(ToolDefinition::qualifiedName)
                .containsExactlyInAnyOrder("http.search", "http.fetch");
    }

    @Test
    void httpToolProviderPassesTimeoutToTransport() {
        Duration[] received = new Duration[1];
        HttpToolProvider.HttpTransport transport = (req, timeout) -> {
            received[0] = timeout;
            return new HttpToolProvider.HttpResponse(
                    200, "ok".getBytes(StandardCharsets.UTF_8), Map.of());
        };
        var provider = new HttpToolProvider("http", "https://api.example.com",
                List.of(new HttpToolProvider.Registration(
                        ToolDefinition.of("http", "search", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        "GET", "/search")),
                transport);

        provider.invoke(ToolCall.of("http.search", Map.of()), Duration.ofSeconds(3));
        assertThat(received[0]).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void httpToolProviderThrowsOnNonOkStatus() {
        var provider = new HttpToolProvider("http", "https://api.example.com",
                List.of(new HttpToolProvider.Registration(
                        ToolDefinition.of("http", "search", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        "GET", "/search")),
                (req, timeout) -> new HttpToolProvider.HttpResponse(
                        500, "err".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThatThrownBy(() -> provider.invoke(
                ToolCall.of("http.search", Map.of()), Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void httpToolProviderDoesNotLeakSensitiveHeaders() {
        var provider = new HttpToolProvider("http", "https://api.example.com",
                List.of(new HttpToolProvider.Registration(
                        ToolDefinition.of("http", "search", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        "GET", "/search")),
                (req, timeout) -> new HttpToolProvider.HttpResponse(
                        200, "ok".getBytes(StandardCharsets.UTF_8),
                        Map.of("Authorization", "Bearer secret", "X-Api-Key", "key123")));

        var result = provider.invoke(ToolCall.of("http.search", Map.of()), Duration.ofSeconds(5));
        assertThat(result.metadata())
                .doesNotContainKey("Authorization")
                .doesNotContainKey("X-Api-Key");
    }

    // ---- OpenApiToolProvider ----

    @Test
    void openApiToolProviderDiscoversConfiguredSchemas() {
        var provider = new OpenApiToolProvider("openapi", "https://api.example.com",
                List.of(new OpenApiToolProvider.Registration(
                        ToolDefinition.of("openapi", "get_page", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        "GET", "/pages/{pageId}")),
                (req, timeout) -> new HttpToolProvider.HttpResponse(
                        200, "ok".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThat(provider.namespace()).isEqualTo("openapi");
        assertThat(provider.discover()).extracting(ToolDefinition::qualifiedName)
                .containsExactly("openapi.get_page");
    }

    @Test
    void openApiToolProviderSubstitutesPathParameters() {
        String[] receivedUrl = new String[1];
        HttpToolProvider.HttpTransport transport = (req, timeout) -> {
            receivedUrl[0] = req.url();
            return new HttpToolProvider.HttpResponse(
                    200, "ok".getBytes(StandardCharsets.UTF_8), Map.of());
        };
        var provider = new OpenApiToolProvider("openapi", "https://api.example.com",
                List.of(new OpenApiToolProvider.Registration(
                        ToolDefinition.of("openapi", "get_page", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        "GET", "/pages/{pageId}")),
                transport);

        provider.invoke(ToolCall.of("openapi.get_page", Map.of("pageId", "123")),
                Duration.ofSeconds(5));
        assertThat(receivedUrl[0]).isEqualTo("https://api.example.com/pages/123");
    }

    @Test
    void openApiToolProviderPercentEncodesPathParameters() {
        String[] receivedUrl = new String[1];
        var provider = new OpenApiToolProvider("openapi", "https://api.example.com",
                List.of(new OpenApiToolProvider.Registration(
                        ToolDefinition.of("openapi", "get_page", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        "GET", "/pages/{pageId}")),
                (request, timeout) -> {
                    receivedUrl[0] = request.url();
                    return new HttpToolProvider.HttpResponse(
                            200, "ok".getBytes(StandardCharsets.UTF_8), Map.of());
                });

        provider.invoke(ToolCall.of(
                "openapi.get_page", Map.of("pageId", "a/b c?admin=true")),
                Duration.ofSeconds(5));

        assertThat(receivedUrl[0])
                .isEqualTo("https://api.example.com/pages/a%2Fb%20c%3Fadmin%3Dtrue");
    }

    @Test
    void openApiToolProviderRejectsUnresolvedPathParametersBeforeTransport() {
        boolean[] transportCalled = {false};
        var provider = new OpenApiToolProvider("openapi", "https://api.example.com",
                List.of(new OpenApiToolProvider.Registration(
                        ToolDefinition.of("openapi", "get_page", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        "GET", "/pages/{pageId}")),
                (request, timeout) -> {
                    transportCalled[0] = true;
                    return new HttpToolProvider.HttpResponse(200, new byte[0], Map.of());
                });

        assertThatThrownBy(() -> provider.invoke(
                ToolCall.of("openapi.get_page", Map.of()), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transportCalled[0]).isFalse();
    }

    @Test
    void openApiToolProviderPassesTimeoutToTransport() {
        Duration[] received = new Duration[1];
        HttpToolProvider.HttpTransport transport = (req, timeout) -> {
            received[0] = timeout;
            return new HttpToolProvider.HttpResponse(
                    200, "ok".getBytes(StandardCharsets.UTF_8), Map.of());
        };
        var provider = new OpenApiToolProvider("openapi", "https://api.example.com",
                List.of(new OpenApiToolProvider.Registration(
                        ToolDefinition.of("openapi", "get_page", "d",
                                ToolDefinition.Schema.empty(), ToolRisk.READ),
                        "GET", "/pages/{pageId}")),
                transport);

        provider.invoke(ToolCall.of("openapi.get_page", Map.of("pageId", "1")),
                Duration.ofSeconds(4));
        assertThat(received[0]).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void stdioMcpProviderRejectsCommandOutsideTrustedAllowlist() {
        var configured = new StdioMcpToolProvider.TrustedCommand(
                "node", List.of("docs-server.js"));
        var allowed = new StdioMcpToolProvider.TrustedCommand(
                "java", List.of("-jar", "approved-server.jar"));

        assertThatThrownBy(() -> new StdioMcpToolProvider(
                "mcp.docs", configured, Set.of(allowed), true,
                Duration.ofMinutes(5), Clock.systemUTC(), unusedStdioSession()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stdioMcpProviderCachesDiscoveryAndUsesOnlyTrustedLaunchCommand() {
        var clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        var trusted = new StdioMcpToolProvider.TrustedCommand(
                "node", List.of("docs-server.js", "--readonly"));
        int[] discoveries = {0};
        StdioMcpToolProvider.TrustedCommand[] invokedWith = new StdioMcpToolProvider.TrustedCommand[1];
        var session = new StdioMcpToolProvider.McpSession() {
            @Override
            public List<ToolDefinition> discover(StdioMcpToolProvider.TrustedCommand command) {
                discoveries[0]++;
                return List.of(definition("mcp.docs", "search", ToolRisk.READ));
            }

            @Override
            public ToolResult invoke(StdioMcpToolProvider.TrustedCommand command,
                                     ToolCall call, Duration timeout) {
                invokedWith[0] = command;
                return ToolResult.of("stdio-ok");
            }
        };
        var provider = new StdioMcpToolProvider(
                "mcp.docs", trusted, Set.of(trusted), true,
                Duration.ofMinutes(5), clock, session);

        assertThat(provider.discover()).extracting(ToolDefinition::qualifiedName)
                .containsExactly("mcp.docs.search");
        assertThat(provider.discover()).hasSize(1);
        assertThat(discoveries[0]).isEqualTo(1);

        var result = provider.invoke(ToolCall.of("mcp.docs.search", Map.of(
                "query", "PulseInk",
                "executable", "malicious.exe",
                "arguments", List.of("--download", "evil"))), Duration.ofSeconds(3));

        assertThat(result.contentText()).isEqualTo("stdio-ok");
        assertThat(invokedWith[0]).isEqualTo(trusted);

        clock.advance(Duration.ofMinutes(6));
        provider.discover();
        assertThat(discoveries[0]).isEqualTo(2);
    }

    @Test
    void disabledStdioMcpProviderExposesNoToolsAndNeverOpensSession() {
        var trusted = new StdioMcpToolProvider.TrustedCommand("node", List.of("docs-server.js"));
        var provider = new StdioMcpToolProvider(
                "mcp.docs", trusted, Set.of(trusted), false,
                Duration.ofMinutes(5), Clock.systemUTC(), unusedStdioSession());

        assertThat(provider.discover()).isEmpty();
        assertThatThrownBy(() -> provider.invoke(
                ToolCall.of("mcp.docs.search", Map.of()), Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void streamableHttpMcpProviderRejectsEndpointOutsideTrustedAllowlist() {
        var configured = new StreamableHttpMcpToolProvider.TrustedEndpoint(
                "https://untrusted.example.com/mcp");
        var allowed = new StreamableHttpMcpToolProvider.TrustedEndpoint(
                "https://mcp.example.com/docs");

        assertThatThrownBy(() -> new StreamableHttpMcpToolProvider(
                "mcp.remote", configured, Set.of(allowed), true,
                Duration.ofMinutes(5), Clock.systemUTC(), unusedHttpMcpTransport()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamableHttpMcpProviderUsesConfiguredEndpointAndPropagatesTimeout() {
        var endpoint = new StreamableHttpMcpToolProvider.TrustedEndpoint(
                "https://mcp.example.com/docs");
        StreamableHttpMcpToolProvider.TrustedEndpoint[] invokedWith =
                new StreamableHttpMcpToolProvider.TrustedEndpoint[1];
        Duration[] receivedTimeout = new Duration[1];
        var transport = new StreamableHttpMcpToolProvider.McpTransport() {
            @Override
            public List<ToolDefinition> discover(
                    StreamableHttpMcpToolProvider.TrustedEndpoint configuredEndpoint) {
                return List.of(definition("mcp.remote", "search", ToolRisk.READ));
            }

            @Override
            public ToolResult invoke(StreamableHttpMcpToolProvider.TrustedEndpoint configuredEndpoint,
                                     ToolCall call, Duration timeout) {
                invokedWith[0] = configuredEndpoint;
                receivedTimeout[0] = timeout;
                return ToolResult.of("http-mcp-ok");
            }
        };
        var provider = new StreamableHttpMcpToolProvider(
                "mcp.remote", endpoint, Set.of(endpoint), true,
                Duration.ofMinutes(5), Clock.systemUTC(), transport);

        provider.discover();
        var result = provider.invoke(ToolCall.of("mcp.remote.search", Map.of(
                "query", "PulseInk", "url", "https://evil.example.com/mcp")),
                Duration.ofSeconds(7));

        assertThat(result.contentText()).isEqualTo("http-mcp-ok");
        assertThat(invokedWith[0]).isEqualTo(endpoint);
        assertThat(receivedTimeout[0]).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void failedMcpRediscoveryMakesCachedToolsUnhealthyAndInvisible() {
        var clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        var endpoint = new StreamableHttpMcpToolProvider.TrustedEndpoint(
                "https://mcp.example.com/docs");
        int[] discoveries = {0};
        var transport = new StreamableHttpMcpToolProvider.McpTransport() {
            @Override
            public List<ToolDefinition> discover(
                    StreamableHttpMcpToolProvider.TrustedEndpoint configuredEndpoint) {
                if (++discoveries[0] > 1) {
                    throw new RuntimeException("Authorization: Bearer discovery-secret");
                }
                return List.of(definition("mcp.remote", "search", ToolRisk.READ));
            }

            @Override
            public ToolResult invoke(StreamableHttpMcpToolProvider.TrustedEndpoint configuredEndpoint,
                                     ToolCall call, Duration timeout) {
                return ToolResult.of("ok");
            }
        };
        var provider = new StreamableHttpMcpToolProvider(
                "mcp.remote", endpoint, Set.of(endpoint), true,
                Duration.ofMinutes(5), clock, transport);
        var profile = AgentProfile.of(
                "researcher", AgentRole.RESEARCHER, Set.of("mcp.remote.search"));
        var registry = new ToolRegistry(List.of(provider), clock);

        assertThat(registry.schemasFor(profile)).hasSize(1);
        clock.advance(Duration.ofMinutes(6));
        var refreshed = registry.refresh();

        assertThat(refreshed.schemasFor(profile)).isEmpty();
        assertThatThrownBy(() -> refreshed.invokeAuthorized(
                profile, ToolCall.of("mcp.remote.search", Map.of()),
                ApprovalState.NOT_REQUIRED, Duration.ofSeconds(3)))
                .isInstanceOf(ToolInvocationException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain("Bearer")
                        .doesNotContain("discovery-secret"));
    }

    @Test
    void mcpInvocationFailureIsNormalizedAndRedactedByRegistry() {
        var trusted = new StdioMcpToolProvider.TrustedCommand("node", List.of("docs-server.js"));
        var session = new StdioMcpToolProvider.McpSession() {
            @Override
            public List<ToolDefinition> discover(StdioMcpToolProvider.TrustedCommand command) {
                return List.of(definition("mcp.docs", "search", ToolRisk.READ));
            }

            @Override
            public ToolResult invoke(StdioMcpToolProvider.TrustedCommand command,
                                     ToolCall call, Duration timeout) {
                throw new RuntimeException("password=hunter2 token=mcp-secret");
            }
        };
        var registry = new ToolRegistry(List.of(new StdioMcpToolProvider(
                "mcp.docs", trusted, Set.of(trusted), true,
                Duration.ofMinutes(5), Clock.systemUTC(), session)));
        var profile = AgentProfile.of(
                "researcher", AgentRole.RESEARCHER, Set.of("mcp.docs.search"));

        assertThatThrownBy(() -> registry.invokeAuthorized(
                profile, ToolCall.of("mcp.docs.search", Map.of()),
                ApprovalState.NOT_REQUIRED, Duration.ofSeconds(3)))
                .isInstanceOf(ToolInvocationException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain("hunter2")
                        .doesNotContain("mcp-secret")
                        .doesNotContain("password")
                        .doesNotContain("token"));
    }

    private static StdioMcpToolProvider.McpSession unusedStdioSession() {
        return new StdioMcpToolProvider.McpSession() {
            @Override
            public List<ToolDefinition> discover(StdioMcpToolProvider.TrustedCommand command) {
                throw new AssertionError("disabled/untrusted provider must not discover");
            }

            @Override
            public ToolResult invoke(StdioMcpToolProvider.TrustedCommand command,
                                     ToolCall call, Duration timeout) {
                throw new AssertionError("disabled/untrusted provider must not invoke");
            }
        };
    }

    private static StreamableHttpMcpToolProvider.McpTransport unusedHttpMcpTransport() {
        return new StreamableHttpMcpToolProvider.McpTransport() {
            @Override
            public List<ToolDefinition> discover(
                    StreamableHttpMcpToolProvider.TrustedEndpoint endpoint) {
                throw new AssertionError("untrusted endpoint must not discover");
            }

            @Override
            public ToolResult invoke(StreamableHttpMcpToolProvider.TrustedEndpoint endpoint,
                                     ToolCall call, Duration timeout) {
                throw new AssertionError("untrusted endpoint must not invoke");
            }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!getZone().equals(zone)) {
                throw new UnsupportedOperationException("test clock is fixed to UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static ToolDefinition definition(String namespace, String localName, ToolRisk risk) {
        return ToolDefinition.of(namespace, localName, "desc", ToolDefinition.Schema.empty(), risk);
    }

    static final class FakeToolProvider implements ToolProvider {
        @Override
        public String namespace() {
            return "builtin";
        }

        @Override
        public List<ToolDefinition> discover() {
            return List.of(definition("builtin", "search", ToolRisk.READ));
        }

        @Override
        public ToolResult invoke(ToolCall call, Duration timeout) {
            return ToolResult.of("ok");
        }
    }
}
