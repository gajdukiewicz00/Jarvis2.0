package org.jarvis.planner.service;

import org.jarvis.planner.dto.TaskDto;
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
        when(taskRepository.findById(1L)).thenReturn(Optional.of(testTask));
        when(taskRepository.save(any(Task.class))).thenReturn(testTask);
        
        // When
        TaskDto result = taskService.completeTask(1L);
        
        // Then
        assertNotNull(result);
        verify(taskRepository).findById(1L);
        verify(taskRepository).save(any(Task.class));
    }
}
