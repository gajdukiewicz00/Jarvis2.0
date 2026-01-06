package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/planner/tasks")
@RequiredArgsConstructor
public class TaskController {
    
    private final TaskService taskService;
    
    /**
     * Get tasks (optionally filtered by status)
     */
    @GetMapping
    public ResponseEntity<List<TaskDto>> getTasks(
            @RequestParam String userId,
            @RequestParam(required = false) TaskStatus status
    ) {
        log.info("GET tasks for user: {}, status: {}", userId, status);
        List<TaskDto> tasks = taskService.getTasks(userId, status);
        return ResponseEntity.ok(tasks);
    }
    
    /**
     * Create new task
     */
    @PostMapping
    public ResponseEntity<TaskDto> createTask(@RequestBody TaskDto dto) {
        log.info("POST task: {}", dto.getTitle());
        TaskDto created = taskService.createTask(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
    
    /**
     * Update task
     */
    @PutMapping("/{id}")
    public ResponseEntity<TaskDto> updateTask(
            @PathVariable Long id,
            @RequestBody TaskDto dto
    ) {
        log.info("PUT task: {}", id);
        TaskDto updated = taskService.updateTask(id, dto);
        return ResponseEntity.ok(updated);
    }
    
    /**
     * Delete task
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        log.info("DELETE task: {}", id);
        taskService.deleteTask(id);
        return ResponseEntity.noContent().build();
    }
    
    /**
     * Mark task as complete
     */
    @PatchMapping("/{id}/complete")
    public ResponseEntity<TaskDto> completeTask(@PathVariable Long id) {
        log.info("COMPLETE task: {}", id);
        TaskDto completed = taskService.completeTask(id);
        return ResponseEntity.ok(completed);
    }
}
