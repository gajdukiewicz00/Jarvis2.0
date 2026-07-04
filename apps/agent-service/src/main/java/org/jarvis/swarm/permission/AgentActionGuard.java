package org.jarvis.swarm.permission;

import org.jarvis.common.safety.SystemPanicState;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.common.safety.ToolPermissionPolicy;
import org.jarvis.swarm.audit.AgentAudit;
import org.jarvis.swarm.task.AgentTask;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The single gate every permissioned agent action passes through. Enforces, in order:
 * <ol>
 *   <li><b>panic</b> — refuse if the global {@link SystemPanicState} is engaged;</li>
 *   <li><b>role ∩ user</b> — the permission must be in the task's granted set;</li>
 *   <li><b>system backstop</b> — dangerous permissions (RUN_SHELL, WRITE_FILES,
 *       NETWORK_ACCESS) must ALSO be allowed by the shared {@link ToolPermissionPolicy},
 *       so a hostile/over-broad request still cannot exceed the platform policy.</li>
 * </ol>
 * Every decision is audited. dryRun is handled by the caller (the executor records a
 * proposed action instead of performing it) — but the permission check still runs, so a
 * dryRun honestly reflects whether the action WOULD be allowed.
 */
@Component
public class AgentActionGuard {

    /** Permissions that, beyond role+user grant, also require the system policy backstop. */
    private static final Set<ToolPermission> SYSTEM_BACKSTOPPED = Set.of(
            ToolPermission.RUN_SHELL, ToolPermission.WRITE_FILES, ToolPermission.NETWORK_ACCESS);

    private final SystemPanicState panic;
    private final ToolPermissionPolicy policy;
    private final AgentAudit audit;

    public AgentActionGuard(SystemPanicState panic, ToolPermissionPolicy policy, AgentAudit audit) {
        this.panic = panic;
        this.policy = policy;
        this.audit = audit;
    }

    /** Whether the global panic switch is currently engaged. */
    public boolean panicEngaged() {
        return panic.isEngaged();
    }

    /** Refuse to even start work when panic is engaged. */
    public void ensureNoPanic(AgentTask task) {
        if (panic.isEngaged()) {
            audit.panicBlocked(task.taskId(), task.correlationId(), task.role().name());
            throw new PanicEngagedException();
        }
    }

    /**
     * Enforce a single permission for a task. Throws {@link PanicEngagedException} or
     * {@link PermissionDeniedException}; otherwise returns having audited the ALLOW.
     */
    public void ensurePermission(AgentTask task, ToolPermission permission) {
        if (panic.isEngaged()) {
            audit.panicBlocked(task.taskId(), task.correlationId(), task.role().name());
            throw new PanicEngagedException();
        }
        if (!task.isGranted(permission)) {
            audit.permissionDenied(task.taskId(), task.correlationId(), task.role().name(),
                    permission.name(), "not granted by role+user");
            throw new PermissionDeniedException(permission, "not granted by role+user");
        }
        if (SYSTEM_BACKSTOPPED.contains(permission) && !policy.granted().contains(permission)) {
            audit.permissionDenied(task.taskId(), task.correlationId(), task.role().name(),
                    permission.name(), "blocked by system policy");
            throw new PermissionDeniedException(permission, "blocked by system policy");
        }
        audit.permissionAllowed(task.taskId(), task.correlationId(), task.role().name(), permission.name());
    }

    /** Non-throwing check used by executors to branch (propose vs perform). */
    public boolean isPermitted(AgentTask task, ToolPermission permission) {
        if (panic.isEngaged() || !task.isGranted(permission)) {
            return false;
        }
        return !SYSTEM_BACKSTOPPED.contains(permission) || policy.granted().contains(permission);
    }
}
