package org.jarvis.swarm.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.queue.AgentTaskService;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.task.AgentTask;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Agent task lifecycle endpoints. All require authentication and resolve the user from
 * the gateway-injected identity; tasks are scoped to their owner.
 */
@RestController
@RequestMapping("/api/v1/agents/tasks")
public class AgentTaskController {

    private final AgentTaskService taskService;
    private final SwarmFeatureGate gate;

    public AgentTaskController(AgentTaskService taskService, SwarmFeatureGate gate) {
        this.taskService = taskService;
        this.gate = gate;
    }

    @PostMapping
    public ResponseEntity<TaskView> create(@Valid @RequestBody CreateTaskRequest request, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        AgentRole role = AgentRole.fromText(request.role());
        Set<ToolPermission> requested = RequestParser.parsePermissions(request.permissions());
        AgentTask task = taskService.submit(userId, role, request.goal(), requested,
                request.dryRun(), null, null, request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(task));
    }

    @GetMapping
    public List<TaskView> list(HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return taskService.listTasks(userId).stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return view(taskService.getTask(id, userId));
    }

    @PostMapping("/{id}/cancel")
    public TaskView cancel(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        taskService.cancel(id, userId);
        return view(taskService.getTask(id, userId));
    }

    @PostMapping("/{id}/pause")
    public TaskView pause(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return view(taskService.pause(id, userId));
    }

    @PostMapping("/{id}/resume")
    public TaskView resume(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return view(taskService.resume(id, userId));
    }

    private TaskView view(AgentTask task) {
        return TaskView.of(task, taskService.resultOf(task.taskId()));
    }
}
