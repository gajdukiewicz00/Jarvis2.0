package org.jarvis.swarm.run;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.config.SwarmProperties;
import org.jarvis.swarm.executor.RoleExecutorRegistry;
import org.jarvis.swarm.executor.RoleResult;
import org.jarvis.swarm.queue.AgentTaskService;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.AgentTaskStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates a multi-role swarm run: fans a single goal out to one child task per role,
 * then assembles an honest combined report from the children's real states (no fabricated
 * success). Reuses the same task engine — so every child obeys the same panic, dryRun,
 * permission, and sandbox gates.
 */
@Slf4j
@Service
public class SwarmCoordinator {

    private final AgentTaskService taskService;
    private final AgentTaskStore taskStore;
    private final RoleExecutorRegistry executors;
    private final SwarmProperties props;

    private final ConcurrentHashMap<String, SwarmRecord> runs = new ConcurrentHashMap<>();

    public SwarmCoordinator(AgentTaskService taskService, AgentTaskStore taskStore,
                            RoleExecutorRegistry executors, SwarmProperties props) {
        this.taskService = taskService;
        this.taskStore = taskStore;
        this.executors = executors;
        this.props = props;
    }

    public SwarmRun submit(String userId, String goal, List<AgentRole> roles,
                           Set<ToolPermission> requested, boolean dryRun) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required");
        }
        if (roles.size() > props.swarmRun().maxRoles()) {
            throw new IllegalArgumentException("too many roles (max " + props.swarmRun().maxRoles() + ")");
        }
        for (AgentRole role : roles) {
            if (!executors.hasRole(role)) {
                throw new IllegalArgumentException("no executor for role " + role);
            }
        }

        String swarmId = "swarm-" + java.util.UUID.randomUUID();
        List<String> taskIds = new ArrayList<>();
        for (AgentRole role : roles) {
            AgentTask child = taskService.submit(userId, role, goal, requested, dryRun, swarmId, swarmId);
            taskIds.add(child.taskId());
        }
        runs.put(swarmId, new SwarmRecord(userId, goal, dryRun));
        log.info("Swarm {} started with {} role(s) for user {}", swarmId, roles.size(), userId);
        return new SwarmRun(swarmId, goal, roles.stream().map(Enum::name).toList(), taskIds, dryRun);
    }

    /** Build the combined report from current child states. */
    public CombinedReport report(String userId, String swarmId) {
        SwarmRecord record = ownedRun(userId, swarmId);
        List<AgentTask> tasks = taskStore.findBySwarm(swarmId).stream()
                .filter(t -> userId.equals(t.userId()))
                .toList();
        if (tasks.isEmpty()) {
            throw new SwarmNotFoundException(swarmId);
        }

        List<RoleOutcome> perRole = new ArrayList<>();
        List<String> rolesUsed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Set<String> risks = new LinkedHashSet<>();
        Set<String> nextActions = new LinkedHashSet<>();
        // A run is complete when every child reached a final outcome (COMPLETED/CANCELLED/FAILED).
        boolean complete = tasks.stream().allMatch(this::isFinal);

        for (AgentTask task : tasks) {
            rolesUsed.add(task.role().name());
            RoleResult result = taskService.resultOf(task.taskId());
            String summary = task.resultSummary() != null ? task.resultSummary()
                    : task.errorMessage() != null ? task.errorMessage() : task.status().name();
            String output = result != null ? result.output() : null;
            List<String> taskRisks = result != null ? result.risks() : task.risks();
            perRole.add(new RoleOutcome(task.role().name(), task.taskId(), task.status().name(),
                    summary, output, task.artifacts(), taskRisks));
            if (task.status() == org.jarvis.swarm.task.AgentTaskStatus.FAILED) {
                failed.add(task.role().name());
            }
            if (taskRisks != null) {
                risks.addAll(taskRisks);
            }
            if (result != null) {
                nextActions.addAll(result.nextActions());
            }
        }
        if (!failed.isEmpty()) {
            nextActions.add("Investigate failed roles: " + String.join(", ", failed));
        }

        return new CombinedReport(swarmId, record.goal(), complete, rolesUsed, perRole,
                failed, List.copyOf(risks), List.copyOf(nextActions));
    }

    /** Poll until all child tasks are terminal or the configured timeout elapses, then report. */
    public CombinedReport awaitAndReport(String userId, String swarmId) {
        ownedRun(userId, swarmId);
        long deadline = System.nanoTime() + props.swarmRun().waitTimeoutSeconds() * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            List<AgentTask> tasks = taskStore.findBySwarm(swarmId);
            if (!tasks.isEmpty() && tasks.stream().allMatch(this::isFinal)) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return report(userId, swarmId);
    }

    private boolean isFinal(AgentTask task) {
        return task.status().isTerminal() || task.status() == org.jarvis.swarm.task.AgentTaskStatus.FAILED;
    }

    private SwarmRecord ownedRun(String userId, String swarmId) {
        SwarmRecord record = runs.get(swarmId);
        if (record == null || !record.userId().equals(userId)) {
            throw new SwarmNotFoundException(swarmId);
        }
        return record;
    }

    private record SwarmRecord(String userId, String goal, boolean dryRun) {}
}
