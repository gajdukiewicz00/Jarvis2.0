package org.jarvis.planner.service;

import org.jarvis.planner.dto.DailyPlanDto;
import org.jarvis.planner.client.UserProfileClient;
import org.jarvis.planner.model.Task;
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
    private UserProfileClient userProfileClient;
    
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
        when(userProfileClient.getPlanningContext(userId)).thenReturn(new UserProfileClient.PlanningContext(
                userId,
                "Test User",
                "Europe/Warsaw",
                "ru",
                List.of(new UserProfileClient.UserGoalPayload("Ship domain cleanup", "active", null, null, null)),
                List.of(new UserProfileClient.UserHabitPayload("Morning review", "DAILY", "morning")),
                List.of(new UserProfileClient.UserPriorityPayload("Backend", 1, null))));
        
        // When
        DailyPlanDto plan = generator.generatePlan(userId, date);
        
        // Then
        assertNotNull(plan);
        assertEquals(userId, plan.getUserId());
        assertEquals(date, plan.getDate());
        assertTrue(plan.getBlocks().containsKey("morning"));
        assertTrue(plan.getBlocks().containsKey("work"));
        assertTrue(plan.getBlocks().containsKey("evening"));
        assertEquals("RULE_BASED_PROFILE_AWARE", plan.getPlanningMode());
        assertEquals("Ship domain cleanup", plan.getFocusGoal());
        assertTrue(plan.getBlocks().get("morning").getFirst().contains("Подъём"));
        assertTrue(plan.getBlocks().get("morning").get(1).contains("Morning review"));
        
        verify(taskRepository).findActiveTasks(userId);
        verify(userProfileClient).getPlanningContext(userId);
    }
}
