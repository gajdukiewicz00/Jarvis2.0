package org.jarvis.planner.service;

import org.jarvis.planner.dto.DailyPlanDto;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.repository.DailyPlanRepository;
import org.jarvis.planner.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyPlanGeneratorTest {
    
    @Mock
    private TaskRepository taskRepository;
    
    @Mock
    private DailyPlanRepository dailyPlanRepository;
    
    @Mock
    private ObjectMapper objectMapper;
    
    @InjectMocks
    private DailyPlanGenerator generator;
    
    @Test
    void testGeneratePlan() {
        // Given
        String userId = "testUser";
        LocalDate date = LocalDate.now();
        List<Task> tasks = new ArrayList<>();
        
        when(taskRepository.findActiveTasks(userId)).thenReturn(tasks);
        
        // When
        DailyPlanDto plan = generator.generatePlan(userId, date);
        
        // Then
        assertNotNull(plan);
        assertEquals(userId, plan.getUserId());
        assertEquals(date, plan.getDate());
        assertTrue(plan.getBlocks().containsKey("morning"));
        assertTrue(plan.getBlocks().containsKey("work"));
        assertTrue(plan.getBlocks().containsKey("evening"));
        
        verify(taskRepository).findActiveTasks(userId);
    }
}
