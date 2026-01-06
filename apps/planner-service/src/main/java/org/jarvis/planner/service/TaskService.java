package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {
    
    private final TaskRepository taskRepository;
    
    public List<TaskDto> getTasks(String userId, TaskStatus status) {
        List<Task> tasks = status != null 
            ? taskRepository.findByUserIdAndStatus(userId, status)
            : taskRepository.findByUserIdOrderByPriorityDescDeadlineAsc(userId);
        
        return tasks.stream().map(this::toDto).toList();
    }
    
    @Transactional
    public TaskDto createTask(TaskDto dto) {
        Task task = new Task();
        task.setUserId(dto.getUserId());
        task.setTitle(dto.getTitle());
        task.setDescription(dto.getDescription());
        task.setCategory(dto.getCategory());
        task.setPriority(dto.getPriority());
        task.setStatus(TaskStatus.TODO);
        task.setDeadline(dto.getDeadline());
        task.setEstimatedDuration(dto.getEstimatedDuration());
        
        Task saved = taskRepository.save(task);
        log.info("Created task: {} for user: {}", saved.getId(), dto.getUserId());
        return toDto(saved);
    }
    
    @Transactional
    public TaskDto updateTask(Long id, TaskDto dto) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Task not found: " + id));
        
        task.setTitle(dto.getTitle());
        task.setDescription(dto.getDescription());
        task.setCategory(dto.getCategory());
        task.setPriority(dto.getPriority());
        task.setStatus(dto.getStatus());
        task.setDeadline(dto.getDeadline());
        task.setEstimatedDuration(dto.getEstimatedDuration());
        
        if (dto.getStatus() == TaskStatus.DONE && task.getCompletedAt() == null) {
            task.setCompletedAt(Instant.now());
        }
        
        Task saved = taskRepository.save(task);
        return toDto(saved);
    }
    
    @Transactional
    public void deleteTask(Long id) {
        taskRepository.deleteById(id);
        log.info("Deleted task: {}", id);
    }
    
    @Transactional
    public TaskDto completeTask(Long id) {
        Task task = taskRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("Task not found: " + id));
        
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(Instant.now());
        
        Task saved = taskRepository.save(task);
        log.info("Completed task: {}", id);
        return toDto(saved);
    }
    
    private TaskDto toDto(Task task) {
        TaskDto dto = new TaskDto();
        dto.setId(task.getId());
        dto.setUserId(task.getUserId());
        dto.setTitle(task.getTitle());
        dto.setDescription(task.getDescription());
        dto.setCategory(task.getCategory());
        dto.setPriority(task.getPriority());
        dto.setStatus(task.getStatus());
        dto.setDeadline(task.getDeadline());
        dto.setEstimatedDuration(task.getEstimatedDuration());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setCompletedAt(task.getCompletedAt());
        return dto;
    }
}
