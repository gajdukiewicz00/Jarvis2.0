package org.jarvis.swarm.task;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Immutable agent-task record. Every state change returns a NEW instance and validates
 * the transition against {@link AgentTaskStatus#canTransitionTo}, throwing
 * {@link InvalidTransitionException} on an illegal move. Times advance via the caller's
 * clock (passed in) so the model stays pure and testable.
 */
public record AgentTask(
        String taskId,
        String userId,
        AgentRole role,
        String goal,
        AgentTaskStatus status,
        Set<ToolPermission> permissionsRequested,
        Set<ToolPermission> permissionsGranted,
        String sandboxPath,
        boolean dryRun,
        int attempt,
        int maxRetries,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        String resultSummary,
        List<String> artifacts,
        List<String> risks,
        String correlationId,
        String swarmId) {

    public AgentTask {
        permissionsRequested = permissionsRequested == null ? Set.of() : Set.copyOf(permissionsRequested);
        permissionsGranted = permissionsGranted == null ? Set.of() : Set.copyOf(permissionsGranted);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }

    public static AgentTask created(String taskId, String userId, AgentRole role, String goal,
                                    Set<ToolPermission> requested, boolean dryRun, int maxRetries,
                                    String correlationId, String swarmId, Instant now) {
        return new AgentTask(taskId, userId, role, goal, AgentTaskStatus.CREATED,
                requested, Set.of(), null, dryRun, 0, maxRetries,
                now, now, null, null, null, null, List.of(), List.of(), correlationId, swarmId);
    }

    private AgentTask to(AgentTaskStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidTransitionException(status, target);
        }
        return copy(target, now);
    }

    public AgentTask queued(Instant now) {
        return to(AgentTaskStatus.QUEUED, now);
    }

    public AgentTask running(Instant now) {
        AgentTask t = to(AgentTaskStatus.RUNNING, now);
        return new AgentTask(t.taskId, t.userId, t.role, t.goal, t.status, t.permissionsRequested,
                t.permissionsGranted, t.sandboxPath, t.dryRun, t.attempt, t.maxRetries,
                t.createdAt, now, startedAt == null ? now : startedAt, t.finishedAt,
                t.errorMessage, t.resultSummary, t.artifacts, t.risks, t.correlationId, t.swarmId);
    }

    public AgentTask paused(Instant now) {
        return to(AgentTaskStatus.PAUSED, now);
    }

    /** Resume a paused task back to QUEUED (not yet started) or RUNNING (already started). */
    public AgentTask resumed(Instant now) {
        AgentTaskStatus target = startedAt == null ? AgentTaskStatus.QUEUED : AgentTaskStatus.RUNNING;
        return to(target, now);
    }

    public AgentTask completed(String summary, List<String> artifacts, List<String> risks, Instant now) {
        AgentTask t = to(AgentTaskStatus.COMPLETED, now);
        return new AgentTask(t.taskId, t.userId, t.role, t.goal, t.status, t.permissionsRequested,
                t.permissionsGranted, t.sandboxPath, t.dryRun, t.attempt, t.maxRetries,
                t.createdAt, now, t.startedAt, now, null, summary, artifacts, risks, t.correlationId, t.swarmId);
    }

    public AgentTask failed(String message, Instant now) {
        AgentTask t = to(AgentTaskStatus.FAILED, now);
        return new AgentTask(t.taskId, t.userId, t.role, t.goal, t.status, t.permissionsRequested,
                t.permissionsGranted, t.sandboxPath, t.dryRun, t.attempt, t.maxRetries,
                t.createdAt, now, t.startedAt, now, message, t.resultSummary, t.artifacts, t.risks,
                t.correlationId, t.swarmId);
    }

    public AgentTask cancelled(Instant now) {
        AgentTask t = to(AgentTaskStatus.CANCELLED, now);
        return new AgentTask(t.taskId, t.userId, t.role, t.goal, t.status, t.permissionsRequested,
                t.permissionsGranted, t.sandboxPath, t.dryRun, t.attempt, t.maxRetries,
                t.createdAt, now, t.startedAt, now,
                t.errorMessage == null ? "cancelled_by_user" : t.errorMessage,
                t.resultSummary, t.artifacts, t.risks, t.correlationId, t.swarmId);
    }

    /** Retry a FAILED task: back to QUEUED with the attempt counter incremented. */
    public AgentTask retried(Instant now) {
        if (attempt >= maxRetries) {
            throw new IllegalStateException("retry budget exhausted");
        }
        AgentTask t = to(AgentTaskStatus.QUEUED, now);
        return new AgentTask(t.taskId, t.userId, t.role, t.goal, t.status, t.permissionsRequested,
                t.permissionsGranted, t.sandboxPath, t.dryRun, attempt + 1, t.maxRetries,
                t.createdAt, now, t.startedAt, null, null, t.resultSummary, t.artifacts, t.risks,
                t.correlationId, t.swarmId);
    }

    public AgentTask withGranted(Set<ToolPermission> granted) {
        return new AgentTask(taskId, userId, role, goal, status, permissionsRequested,
                granted, sandboxPath, dryRun, attempt, maxRetries,
                createdAt, updatedAt, startedAt, finishedAt, errorMessage, resultSummary,
                artifacts, risks, correlationId, swarmId);
    }

    public AgentTask withSandbox(String path) {
        return new AgentTask(taskId, userId, role, goal, status, permissionsRequested,
                permissionsGranted, path, dryRun, attempt, maxRetries,
                createdAt, updatedAt, startedAt, finishedAt, errorMessage, resultSummary,
                artifacts, risks, correlationId, swarmId);
    }

    public boolean isGranted(ToolPermission permission) {
        return permissionsGranted.contains(permission);
    }

    private AgentTask copy(AgentTaskStatus newStatus, Instant now) {
        return new AgentTask(taskId, userId, role, goal, newStatus, permissionsRequested,
                permissionsGranted, sandboxPath, dryRun, attempt, maxRetries,
                createdAt, now, startedAt, finishedAt, errorMessage, resultSummary,
                artifacts, risks, correlationId, swarmId);
    }
}
