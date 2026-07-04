package org.jarvis.swarm.executor;

import org.jarvis.swarm.role.AgentRole;

/**
 * A role-specific, REAL workflow (never a hardcoded success). Each implementation does
 * genuine work: builds a plan, scans content, writes sandbox files, or proposes/executes
 * a guarded command. dryRun mode returns proposed actions without side effects.
 */
public interface RoleExecutor {

    AgentRole role();

    RoleResult execute(ExecutionContext ctx);
}
