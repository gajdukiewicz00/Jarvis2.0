package org.jarvis.swarm.executor;

import org.jarvis.swarm.role.AgentRole;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Collects all role executors and dispatches by role. */
@Component
public class RoleExecutorRegistry {

    private final Map<AgentRole, RoleExecutor> executors = new EnumMap<>(AgentRole.class);

    public RoleExecutorRegistry(List<RoleExecutor> beans) {
        for (RoleExecutor executor : beans) {
            executors.put(executor.role(), executor);
        }
    }

    public RoleExecutor forRole(AgentRole role) {
        RoleExecutor executor = executors.get(role);
        if (executor == null) {
            throw new IllegalStateException("No executor registered for role " + role);
        }
        return executor;
    }

    public boolean hasRole(AgentRole role) {
        return executors.containsKey(role);
    }
}
