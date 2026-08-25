package com.pulseink.agent.tool;

import com.pulseink.agent.orchestration.AgentProfile;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory governed registry of tools discovered from one or more {@link ToolProvider}s. The
 * registry holds an immutable snapshot of discovered tools, their enabled/health state and the
 * provider that owns each tool. A model can only see {@link #schemasFor(AgentProfile)} and can
 * never bypass {@link #invokeAuthorized}.
 */
public final class ToolRegistry {

    private final Map<String, RegisteredTool> tools;
    private final Map<String, ProviderHealth> health;
    private final Map<String, ToolProvider> providersByNamespace;
    private final Clock clock;
    private final Set<String> disabledTools;

    public ToolRegistry(List<ToolProvider> providers) {
        this(providers, Clock.systemUTC(), Set.of());
    }

    public ToolRegistry(List<ToolProvider> providers, Clock clock) {
        this(providers, clock, Set.of());
    }

    public ToolRegistry(List<ToolProvider> providers, Clock clock, Set<String> disabledTools) {
        this.clock = clock;
        this.disabledTools = Set.copyOf(disabledTools);
        var toolMap = new LinkedHashMap<String, RegisteredTool>();
        var healthMap = new LinkedHashMap<String, ProviderHealth>();
        var providerMap = new LinkedHashMap<String, ToolProvider>();
        Instant now = clock.instant();
        var seenNamespaces = new HashSet<String>();
        for (var provider : providers) {
            String ns = ToolDefinition.requireValidNamespace(provider.namespace());
            if (!seenNamespaces.add(ns)) {
                throw new IllegalArgumentException("duplicate namespace: " + ns);
            }
            providerMap.put(ns, provider);
            List<ToolDefinition> discovered;
            try {
                discovered = List.copyOf(provider.discover());
            } catch (RuntimeException e) {
                healthMap.put(ns, new ProviderHealth(false, now));
                continue;
            }
            healthMap.put(ns, new ProviderHealth(true, now));
            for (var def : discovered) {
                String qn = def.qualifiedName();
                if (!def.namespace().equals(ns)) {
                    throw new IllegalArgumentException("namespace mismatch for " + qn);
                }
                if (toolMap.containsKey(qn)) {
                    throw new IllegalArgumentException("duplicate tool: " + qn);
                }
                toolMap.put(qn, new RegisteredTool(def, provider, !disabledTools.contains(qn)));
            }
        }
        this.tools = Collections.unmodifiableMap(toolMap);
        this.health = Collections.unmodifiableMap(healthMap);
        this.providersByNamespace = Collections.unmodifiableMap(providerMap);
    }

    private ToolRegistry(Map<String, RegisteredTool> tools,
                         Map<String, ProviderHealth> health,
                         Map<String, ToolProvider> providers,
                         Clock clock,
                         Set<String> disabledTools) {
        this.tools = tools;
        this.health = health;
        this.providersByNamespace = providers;
        this.clock = clock;
        this.disabledTools = disabledTools;
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /**
     * Read-only metadata snapshot for product pages: all discovered tool definitions sorted by
     * qualified name. It intentionally exposes only the public definition fields and never the
     * owning provider, health gate or invocation path.
     */
    public List<ToolDefinition> definitionSnapshot() {
        return tools.values().stream()
                .map(RegisteredTool::definition)
                .sorted(java.util.Comparator.comparing(ToolDefinition::qualifiedName))
                .toList();
    }

    /**
     * Returns the immutable schemas visible to the given profile: only tools that are in the
     * allowlist, enabled and healthy. The Provider or any internal registry state is never exposed.
     */
    public List<ToolDefinition> schemasFor(AgentProfile profile) {
        var result = new ArrayList<ToolDefinition>();
        for (var tool : tools.values()) {
            String qn = tool.definition().qualifiedName();
            if (profile.allowedTools().contains(qn) && tool.enabled() && isHealthy(tool)) {
                result.add(tool.definition());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Governed invocation: lookup -> disabled/health gate -> policy pre-checks (allowlist, risk,
     * schema, timeout) -> provider invoke -> response-size check -> failure normalization. The
     * policy is never skipped.
     */
    public ToolResult invokeAuthorized(AgentProfile profile, ToolCall call,
                                       ApprovalState approval, Duration timeout) {
        var tool = tools.get(call.qualifiedName());
        if (tool == null) {
            throw new ToolAuthorizationException("tool not registered: " + call.qualifiedName());
        }
        if (!tool.enabled()) {
            throw new ToolAuthorizationException("tool disabled: " + call.qualifiedName());
        }
        if (!isHealthy(tool)) {
            throw new ToolInvocationException("tool unavailable: " + call.qualifiedName());
        }
        var policy = ToolPolicy.forProfile(profile);
        policy.authorize(tool.definition(), call, approval);
        policy.validateTimeout(timeout);
        ToolResult result;
        try {
            result = tool.provider().invoke(call, timeout);
        } catch (RuntimeException e) {
            throw ToolInvocationException.from(e);
        }
        policy.validateResponseSize(result);
        return result;
    }

    /**
     * Re-discovers from every provider and returns a new immutable registry. Providers whose
     * discovery fails keep their previously discovered tools but are marked unhealthy with a
     * refreshed timestamp.
     */
    public ToolRegistry refresh() {
        var newToolMap = new LinkedHashMap<String, RegisteredTool>();
        var newHealthMap = new LinkedHashMap<String, ProviderHealth>();
        Instant now = clock.instant();

        for (var entry : providersByNamespace.entrySet()) {
            String ns = entry.getKey();
            var provider = entry.getValue();
            List<ToolDefinition> discovered;
            try {
                discovered = List.copyOf(provider.discover());
            } catch (RuntimeException e) {
                newHealthMap.put(ns, new ProviderHealth(false, now));
                for (var tool : tools.values()) {
                    if (tool.provider().namespace().equals(ns)) {
                        newToolMap.put(tool.definition().qualifiedName(), tool);
                    }
                }
                continue;
            }
            newHealthMap.put(ns, new ProviderHealth(true, now));
            for (var def : discovered) {
                String qn = def.qualifiedName();
                if (!def.namespace().equals(ns)) {
                    throw new IllegalArgumentException("namespace mismatch for " + qn);
                }
                if (newToolMap.containsKey(qn)) {
                    throw new IllegalArgumentException("duplicate tool: " + qn);
                }
                newToolMap.put(qn, new RegisteredTool(def, provider, !disabledTools.contains(qn)));
            }
        }

        return new ToolRegistry(
                Collections.unmodifiableMap(newToolMap),
                Collections.unmodifiableMap(newHealthMap),
                providersByNamespace,
                clock,
                disabledTools);
    }

    private boolean isHealthy(RegisteredTool tool) {
        var h = health.get(tool.provider().namespace());
        return h != null && h.healthy();
    }

    private record RegisteredTool(ToolDefinition definition, ToolProvider provider, boolean enabled) {
    }

    private record ProviderHealth(boolean healthy, Instant timestamp) {
    }
}
