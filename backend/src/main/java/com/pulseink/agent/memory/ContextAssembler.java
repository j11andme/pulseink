package com.pulseink.agent.memory;

/**
 * Role-context assembly boundary. Engines call it before every model boundary with the exact
 * role profile, dependencies and task history; the result is deterministic per input.
 */
public interface ContextAssembler {

    AgentContext assemble(ContextAssemblyRequest request);
}
