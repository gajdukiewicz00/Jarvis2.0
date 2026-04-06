package org.jarvis.planner.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.model.TaskSource;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.service.TaskService;
import org.jarvis.planner.tooling.ToolRequestService;
import org.jarvis.planner.tooling.dto.CompleteTodoRequest;
import org.jarvis.planner.tooling.dto.CreateTodoRequest;
import org.jarvis.planner.tooling.dto.ListTodosRequest;
import org.jarvis.planner.tooling.dto.UpdateTodoRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/tools/todo")
@RequiredArgsConstructor
@Validated
public class ToolTodoController {

    private final TaskService taskService;
    private final ToolRequestService toolRequestService;

    @PostMapping("/create")
    public ResponseEntity<TaskDto> createTodo(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody CreateTodoRequest request) {

        String requestHash = toolRequestService.hashRequest(request);
        Optional<TaskDto> cached = toolRequestService.loadCachedResponse(
                idempotencyKey,
                "create_todo",
                userId,
                requestHash,
                TaskDto.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }

        TaskDto task = new TaskDto();
        task.setUserId(userId);
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setDueDate(request.getDueDate());
        task.setPriority(request.getPriority());
        task.setTags(request.getTags());
        task.setSource(TaskSource.AI);
        task.setCreatedBy("ai");
        task.setUpdatedBy("ai");

        TaskDto created = taskService.createTask(task);
        toolRequestService.storeResponse(idempotencyKey, "create_todo", userId, requestHash, created);

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/update")
    public ResponseEntity<TaskDto> updateTodo(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody UpdateTodoRequest request) {

        String requestHash = toolRequestService.hashRequest(request);
        Optional<TaskDto> cached = toolRequestService.loadCachedResponse(
                idempotencyKey,
                "update_todo",
                userId,
                requestHash,
                TaskDto.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }

        TaskDto task = new TaskDto();
        task.setId(request.getId());
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setDueDate(request.getDueDate());
        task.setPriority(request.getPriority());
        task.setStatus(request.getStatus());
        task.setTags(request.getTags());
        task.setUpdatedBy("ai");
        task.setUserId(userId);

        TaskDto updated = taskService.updateTask(request.getId(), userId, task);
        toolRequestService.storeResponse(idempotencyKey, "update_todo", userId, requestHash, updated);

        return ResponseEntity.ok(updated);
    }

    @PostMapping("/complete")
    public ResponseEntity<TaskDto> completeTodo(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody CompleteTodoRequest request) {

        String requestHash = toolRequestService.hashRequest(request);
        Optional<TaskDto> cached = toolRequestService.loadCachedResponse(
                idempotencyKey,
                "complete_todo",
                userId,
                requestHash,
                TaskDto.class);
        if (cached.isPresent()) {
            return ResponseEntity.ok(cached.get());
        }

        TaskDto completed = taskService.completeTask(request.getId(), userId);
        toolRequestService.storeResponse(idempotencyKey, "complete_todo", userId, requestHash, completed);

        return ResponseEntity.ok(completed);
    }

    @PostMapping("/list")
    public ResponseEntity<List<TaskDto>> listTodos(
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody ListTodosRequest request) {
        TaskStatus status = request.getStatus();
        log.info("Tool list_todos request for userId={}, status={}", userId, status);
        List<TaskDto> tasks = taskService.getTasks(userId, status);

        if (request.getFrom() == null && request.getTo() == null
                && (request.getTags() == null || request.getTags().isEmpty())) {
            return ResponseEntity.ok(tasks);
        }

        List<TaskDto> filtered = tasks.stream()
                .filter(task -> withinRange(task.getDueDate(), request.getFrom(), request.getTo()))
                .filter(task -> hasTags(task.getTags(), request.getTags()))
                .toList();

        return ResponseEntity.ok(filtered);
    }

    private boolean withinRange(Instant dueDate, Instant from, Instant to) {
        if (from == null && to == null) {
            return true;
        }
        if (dueDate == null) {
            return false;
        }
        boolean afterFrom = from == null || !dueDate.isBefore(from);
        boolean beforeTo = to == null || !dueDate.isAfter(to);
        return afterFrom && beforeTo;
    }

    private boolean hasTags(List<String> taskTags, List<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) {
            return true;
        }
        if (taskTags == null || taskTags.isEmpty()) {
            return false;
        }
        return requiredTags.stream().allMatch(taskTags::contains);
    }

}
