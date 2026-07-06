package org.jarvis.swarm.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jarvis.common.safety.ToolPermission;
import org.jarvis.swarm.queue.AgentTaskService;
import org.jarvis.swarm.role.AgentRole;
import org.jarvis.swarm.sandbox.Sandbox;
import org.jarvis.swarm.sandbox.SandboxManager;
import org.jarvis.swarm.task.AgentTask;
import org.jarvis.swarm.task.ArtifactNotFoundException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final SandboxManager sandboxManager;

    public AgentTaskController(AgentTaskService taskService, SwarmFeatureGate gate, SandboxManager sandboxManager) {
        this.taskService = taskService;
        this.gate = gate;
        this.sandboxManager = sandboxManager;
    }

    @PostMapping
    public ResponseEntity<TaskView> create(@Valid @RequestBody CreateTaskRequest request, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        AgentRole role = AgentRole.fromText(request.role());
        Set<ToolPermission> requested = RequestParser.parsePermissions(request.permissions());
        AgentTask task = taskService.submit(userId, role, request.goal(), requested,
                request.dryRun(), null, null, request.idempotencyKey(), request.approvalRequired());
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

    /** Approve a pending CODER patch proposal: applies it to the sandbox. */
    @PostMapping("/{id}/approve")
    public TaskView approve(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return view(taskService.approve(id, userId));
    }

    /** Reject a pending CODER patch proposal: discards it, nothing is ever applied. */
    @PostMapping("/{id}/reject")
    public TaskView reject(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return view(taskService.reject(id, userId));
    }

    /** Revert an applied CODER patch: restores the sandbox to its pre-apply snapshot. */
    @PostMapping("/{id}/rollback")
    public TaskView rollback(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        return view(taskService.rollback(id, userId));
    }

    /** Download the CODER-produced DIFF.patch for a task, if one has been written. */
    @GetMapping("/{id}/artifacts/diff")
    public ResponseEntity<Resource> downloadDiff(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        AgentTask task = taskService.getTask(id, userId);
        String stored = task.artifacts().stream()
                .filter(a -> a.endsWith("DIFF.patch"))
                .findFirst()
                .orElseThrow(() -> new ArtifactNotFoundException(id, "DIFF.patch"));
        Path resolved = validateArtifact(task, stored);
        if (!Files.isRegularFile(resolved)) {
            throw new ArtifactNotFoundException(id, "DIFF.patch");
        }
        return fileResponse(resolved, "diff.patch");
    }

    /** Download a rendered combined report for a task (summary, risks, artifacts, output). */
    @GetMapping("/{id}/artifacts/report")
    public ResponseEntity<Resource> downloadReport(@PathVariable String id, HttpServletRequest http) {
        gate.ensureEnabled();
        String userId = UserContext.requireUserId(http);
        AgentTask task = taskService.getTask(id, userId);
        String report = TaskReportRenderer.render(task, taskService.resultOf(id));
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/markdown"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitizeFilename(id) + "-report.md\"")
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    private TaskView view(AgentTask task) {
        return TaskView.of(task, taskService.resultOf(task.taskId()));
    }

    private Path validateArtifact(AgentTask task, String storedPath) {
        if (task.sandboxPath() == null) {
            throw new ArtifactNotFoundException(task.taskId(), storedPath);
        }
        Sandbox sandbox = new Sandbox(task.taskId(), Path.of(task.sandboxPath()));
        return sandboxManager.validateArtifactPath(sandbox, storedPath);
    }

    private ResponseEntity<Resource> fileResponse(Path resolved, String downloadName) {
        Resource resource = new FileSystemResource(resolved);
        long size = sandboxManager.sizeOrZero(resolved);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitizeFilename(downloadName) + "\"")
                .contentLength(size)
                .body(resource);
    }

    /** Strip anything but a conservative filename charset before it lands in a response header. */
    private String sanitizeFilename(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
