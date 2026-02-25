package org.jarvis.planner.service;

import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.exception.TaskNotFoundException;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskCategory;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {
    
    @Mock
    private TaskRepository taskRepository;
    
    @InjectMocks
    private TaskService taskService;
    
    private Task testTask;
    
    @BeforeEach
    void setUp() {
        testTask = new Task();
        testTask.setId(1L);
        testTask.setUserId("testUser");
        testTask.setTitle("Test Task");
        testTask.setDescription("Test Description");
        testTask.setCategory(TaskCategory.WORK);
        testTask.setPriority(TaskPriority.HIGH);
        testTask.setStatus(TaskStatus.TODO);
    }
    
    @Test
    void testGetTasks_WithStatus() {
        // Given
        List<Task> tasks = Arrays.asList(testTask);
        when(taskRepository.findByUserIdAndStatus("testUser", TaskStatus.TODO))
            .thenReturn(tasks);
        
        // When
        List<TaskDto> result = taskService.getTasks("testUser", TaskStatus.TODO);
        
        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Test Task", result.get(0).getTitle());
        verify(taskRepository).findByUserIdAndStatus("testUser", TaskStatus.TODO);
    }
    
    @Test
    void testCreateTask() {
        // Given
        TaskDto dto = new TaskDto();
        dto.setUserId("testUser");
        dto.setTitle("New Task");
        dto.setCategory(TaskCategory.PERSONAL);
        dto.setPriority(TaskPriority.MEDIUM);
        
        when(taskRepository.save(any(Task.class))).thenReturn(testTask);
        
        // When
        TaskDto result = taskService.createTask(dto);
        
        // Then
        assertNotNull(result);
        verify(taskRepository).save(any(Task.class));
    }
    
    @Test
    void testCompleteTask() {
        // Given
        when(taskRepository.findByIdAndUserId(1L, "testUser")).thenReturn(Optional.of(testTask));
        when(taskRepository.save(any(Task.class))).thenReturn(testTask);
        
        // When
        TaskDto result = taskService.completeTask(1L, "testUser");
        
        // Then
        assertNotNull(result);
        verify(taskRepository).findByIdAndUserId(1L, "testUser");
        verify(taskRepository).save(any(Task.class));
    }

    @Test
    void testCompleteTask_ThrowsWhenTaskOwnedByAnotherUser() {
        // Given
        when(taskRepository.findByIdAndUserId(1L, "otherUser")).thenReturn(Optional.empty());

        // When / Then
        assertThrows(TaskNotFoundException.class, () -> taskService.completeTask(1L, "otherUser"));
        verify(taskRepository, never()).save(any(Task.class));
    }
}
