package org.jarvis.swarm.task.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * JPA mapping of the immutable {@link AgentTask} domain record. The record itself stays
 * the single source of truth for lifecycle rules (transitions, validation); this entity
 * is a plain, mutable persistence shape that mirrors it 1:1 for {@link JpaAgentTaskStore}.
 */
@Entity
@Table(name = "agent_task")
@Getter
@Setter
public class AgentTaskEntity {

    @Id
    @Column(name = "task_id", nullable = false, updatable = false, length = 64)
    private String taskId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private AgentRole role;

    @Column(name = "goal", nullable = false, columnDefinition = "TEXT")
    private String goal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AgentTaskStatus status;

    @Convert(converter = ToolPermissionSetConverter.class)
    @Column(name = "permissions_requested", length = 500)
    private Set<ToolPermission> permissionsRequested = Set.of();

    @Convert(converter = ToolPermissionSetConverter.class)
    @Column(name = "permissions_granted", length = 500)
    private Set<ToolPermission> permissionsGranted = Set.of();

    @Column(name = "sandbox_path", length = 1000)
    private String sandboxPath;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "result_summary", columnDefinition = "TEXT")
    private String resultSummary;

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "artifacts", columnDefinition = "TEXT")
    private List<String> artifacts = List.of();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "risks", columnDefinition = "TEXT")
    private List<String> risks = List.of();

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "swarm_id")
    private String swarmId;

    protected AgentTaskEntity() {
        // JPA
    }

    public static AgentTaskEntity fromDomain(AgentTask task) {
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.taskId = task.taskId();
        entity.userId = task.userId();
        entity.role = task.role();
        entity.goal = task.goal();
        entity.status = task.status();
        entity.permissionsRequested = task.permissionsRequested();
        entity.permissionsGranted = task.permissionsGranted();
        entity.sandboxPath = task.sandboxPath();
        entity.dryRun = task.dryRun();
        entity.attempt = task.attempt();
        entity.maxRetries = task.maxRetries();
        entity.createdAt = task.createdAt();
        entity.updatedAt = task.updatedAt();
        entity.startedAt = task.startedAt();
        entity.finishedAt = task.finishedAt();
        entity.errorMessage = task.errorMessage();
        entity.resultSummary = task.resultSummary();
        entity.artifacts = task.artifacts();
        entity.risks = task.risks();
        entity.correlationId = task.correlationId();
        entity.swarmId = task.swarmId();
        return entity;
    }

    public AgentTask toDomain() {
        return new AgentTask(taskId, userId, role, goal, status,
                permissionsRequested, permissionsGranted, sandboxPath, dryRun,
                attempt, maxRetries, createdAt, updatedAt, startedAt, finishedAt,
                errorMessage, resultSummary, artifacts, risks, correlationId, swarmId);
    }
}
