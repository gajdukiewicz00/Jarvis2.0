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

    @Test
    void testGetTasks_WithoutStatusUsesPriorityOrdering() {
        // Given
        when(taskRepository.findByUserIdOrderByPriorityDescDueDateAsc("testUser"))
                .thenReturn(List.of(testTask));

        // When
        List<TaskDto> result = taskService.getTasks("testUser", null);

        // Then
        assertEquals(1, result.size());
        verify(taskRepository).findByUserIdOrderByPriorityDescDueDateAsc("testUser");
        verify(taskRepository, never()).findByUserIdAndStatus(any(), any());
    }

    @Test
    void testUpdateTask_AppliesAllProvidedFieldsAndDefaultsUpdatedByToDtoValue() {
        // Given
        when(taskRepository.findByIdAndUserId(1L, "testUser")).thenReturn(Optional.of(testTask));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskDto dto = new TaskDto();
        dto.setTitle("Updated title");
        dto.setDescription("Updated description");
        dto.setCategory(TaskCategory.WORK);
        dto.setPriority(TaskPriority.URGENT);
        dto.setStatus(TaskStatus.IN_PROGRESS);
        dto.setDueDate(Instant.parse("2026-08-01T00:00:00Z"));
        dto.setEstimatedDuration(45);
        dto.setTags(List.of("urgent"));
        dto.setUpdatedBy("editor-1");

        // When
        TaskDto result = taskService.updateTask(1L, "testUser", dto);

        // Then
        assertEquals("Updated title", result.getTitle());
        assertEquals("Updated description", result.getDescription());
        assertEquals(TaskCategory.WORK, result.getCategory());
        assertEquals(TaskPriority.URGENT, result.getPriority());
        assertEquals(TaskStatus.IN_PROGRESS, result.getStatus());
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), result.getDueDate());
        assertEquals(45, result.getEstimatedDuration());
        assertEquals(List.of("urgent"), result.getTags());
        assertEquals("editor-1", result.getUpdatedBy());
        assertNull(result.getCompletedAt());
    }

    @Test
    void testUpdateTask_WithoutUpdatedByFallsBackToUserId() {
        // Given
        when(taskRepository.findByIdAndUserId(1L, "testUser")).thenReturn(Optional.of(testTask));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskDto dto = new TaskDto();
        dto.setTitle("Only title changes");

        // When
        TaskDto result = taskService.updateTask(1L, "testUser", dto);

        // Then
        assertEquals("Only title changes", result.getTitle());
        assertEquals("testUser", result.getUpdatedBy());
        // Untouched fields keep their original values
        assertEquals("Test Description", result.getDescription());
        assertEquals(TaskCategory.WORK, result.getCategory());
    }

    @Test
    void testUpdateTask_SettingStatusToDoneStampsCompletedAtWhenNotAlreadySet() {
        // Given
        testTask.setCompletedAt(null);
        when(taskRepository.findByIdAndUserId(1L, "testUser")).thenReturn(Optional.of(testTask));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskDto dto = new TaskDto();
        dto.setStatus(TaskStatus.DONE);

        // When
        TaskDto result = taskService.updateTask(1L, "testUser", dto);

        // Then
        assertEquals(TaskStatus.DONE, result.getStatus());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    void testUpdateTask_SettingStatusToDoneKeepsExistingCompletedAt() {
        // Given
        Instant originalCompletion = Instant.parse("2026-01-01T00:00:00Z");
        testTask.setCompletedAt(originalCompletion);
        when(taskRepository.findByIdAndUserId(1L, "testUser")).thenReturn(Optional.of(testTask));
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskDto dto = new TaskDto();
        dto.setStatus(TaskStatus.DONE);

        // When
        TaskDto result = taskService.updateTask(1L, "testUser", dto);

        // Then
        assertEquals(originalCompletion, result.getCompletedAt());
    }

    @Test
    void testUpdateTask_ThrowsWhenTaskNotFoundForUser() {
        // Given
        when(taskRepository.findByIdAndUserId(1L, "otherUser")).thenReturn(Optional.empty());

        TaskDto dto = new TaskDto();
        dto.setTitle("Irrelevant");

        // When / Then
        assertThrows(TaskNotFoundException.class, () -> taskService.updateTask(1L, "otherUser", dto));
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    void testDeleteTask_DeletesWhenOwnedByUser() {
        // Given
        when(taskRepository.deleteByIdAndUserId(1L, "testUser")).thenReturn(1L);

        // When
        taskService.deleteTask(1L, "testUser");

        // Then
        verify(taskRepository).deleteByIdAndUserId(1L, "testUser");
    }

    @Test
    void testDeleteTask_ThrowsWhenNothingDeleted() {
        // Given
        when(taskRepository.deleteByIdAndUserId(1L, "otherUser")).thenReturn(0L);

        // When / Then
        assertThrows(TaskNotFoundException.class, () -> taskService.deleteTask(1L, "otherUser"));
    }
}
