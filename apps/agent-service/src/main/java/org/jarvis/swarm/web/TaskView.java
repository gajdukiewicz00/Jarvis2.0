package org.jarvis.swarm.web;

import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** API view of an agent task (plus its role result when available). */
public record TaskView(
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
        String swarmId,
        RoleResult result) {

    public static TaskView of(AgentTask task, RoleResult result) {
        return new TaskView(
                task.taskId(), task.userId(), task.role(), task.goal(), task.status(),
                task.permissionsRequested(), task.permissionsGranted(), task.sandboxPath(), task.dryRun(),
                task.attempt(), task.maxRetries(), task.createdAt(), task.updatedAt(), task.startedAt(),
                task.finishedAt(), task.errorMessage(), task.resultSummary(), task.artifacts(), task.risks(),
                task.correlationId(), task.swarmId(), result);
    }
}
