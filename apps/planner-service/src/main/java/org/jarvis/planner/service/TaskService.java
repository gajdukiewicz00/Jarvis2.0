package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.exception.TaskNotFoundException;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskSource;
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
            : taskRepository.findByUserIdOrderByPriorityDescDueDateAsc(userId);
        
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
        task.setDueDate(dto.getDueDate());
        task.setEstimatedDuration(dto.getEstimatedDuration());
        task.setTags(dto.getTags());
        task.setSource(dto.getSource() != null ? dto.getSource() : TaskSource.MANUAL);
        task.setCreatedBy(dto.getCreatedBy() != null ? dto.getCreatedBy() : dto.getUserId());
        task.setUpdatedBy(dto.getUpdatedBy() != null ? dto.getUpdatedBy() : dto.getUserId());
        
        Task saved = taskRepository.save(task);
        log.info("Created task: {} for user: {}", saved.getId(), dto.getUserId());
        return toDto(saved);
    }
    
    @Transactional
    public TaskDto updateTask(Long id, String userId, TaskDto dto) {
        Task task = taskRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new TaskNotFoundException(id, userId));

        if (dto.getTitle() != null) {
            task.setTitle(dto.getTitle());
        }
        if (dto.getDescription() != null) {
            task.setDescription(dto.getDescription());
        }
        if (dto.getCategory() != null) {
            task.setCategory(dto.getCategory());
        }
        if (dto.getPriority() != null) {
            task.setPriority(dto.getPriority());
        }
        if (dto.getStatus() != null) {
            task.setStatus(dto.getStatus());
        }
        if (dto.getDueDate() != null) {
            task.setDueDate(dto.getDueDate());
        }
        if (dto.getEstimatedDuration() != null) {
            task.setEstimatedDuration(dto.getEstimatedDuration());
        }
        if (dto.getTags() != null) {
            task.setTags(dto.getTags());
        }
        if (dto.getUpdatedBy() != null) {
            task.setUpdatedBy(dto.getUpdatedBy());
        } else {
            task.setUpdatedBy(userId);
        }

        if (dto.getStatus() == TaskStatus.DONE && task.getCompletedAt() == null) {
            task.setCompletedAt(Instant.now());
        }
        
        Task saved = taskRepository.save(task);
        return toDto(saved);
    }
    
    @Transactional
    public void deleteTask(Long id, String userId) {
        long deleted = taskRepository.deleteByIdAndUserId(id, userId);
        if (deleted == 0) {
            throw new TaskNotFoundException(id, userId);
        }
        log.info("Deleted task: {} for user: {}", id, userId);
    }
    
    @Transactional
    public TaskDto completeTask(Long id, String userId) {
        Task task = taskRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new TaskNotFoundException(id, userId));
        
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(Instant.now());
        task.setUpdatedBy(userId);
        
        Task saved = taskRepository.save(task);
        log.info("Completed task: {} for user: {}", id, userId);
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
        dto.setDueDate(task.getDueDate());
        dto.setTags(task.getTags());
        dto.setSource(task.getSource());
        dto.setCreatedBy(task.getCreatedBy());
        dto.setUpdatedBy(task.getUpdatedBy());
        dto.setEstimatedDuration(task.getEstimatedDuration());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());
        dto.setCompletedAt(task.getCompletedAt());
        return dto;
    }
}
