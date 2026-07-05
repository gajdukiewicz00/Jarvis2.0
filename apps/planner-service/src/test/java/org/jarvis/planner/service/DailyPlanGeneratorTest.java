package org.jarvis.planner.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.jarvis.planner.dto.DailyPlanDto;
import org.jarvis.planner.client.UserProfileClient;
import org.jarvis.planner.model.DailyPlan;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
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
import java.util.Optional;

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

    @Mock
    private RecurringTaskGenerator recurringTaskGenerator;

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
        verify(recurringTaskGenerator).generateOccurrencesForDate(userId, date);
    }

    @Test
    void testGeneratePlanTriggersRecurringOccurrenceGenerationBeforeGatheringActiveTasks() {
        // Given
        String userId = "recurringUser";
        LocalDate date = LocalDate.now();

        when(taskRepository.findActiveTasks(userId)).thenReturn(new ArrayList<>());
        when(userProfileClient.getPlanningContext(userId)).thenReturn(UserProfileClient.PlanningContext.empty());

        // When
        generator.generatePlan(userId, date);

        // Then
        verify(recurringTaskGenerator).generateOccurrencesForDate(userId, date);
    }

    @Test
    void testGeneratePlanWithEmptyProfileDataUsesFallbackActivities() {
        // Given
        String userId = "emptyUser";
        LocalDate date = LocalDate.now();

        when(taskRepository.findActiveTasks(userId)).thenReturn(new ArrayList<>());
        when(userProfileClient.getPlanningContext(userId)).thenReturn(UserProfileClient.PlanningContext.empty());

        // When
        DailyPlanDto plan = generator.generatePlan(userId, date);

        // Then
        assertNull(plan.getFocusGoal());
        assertTrue(plan.getPriorityCategories().isEmpty());
        assertTrue(plan.getBlocks().get("morning").contains("Утренняя зарядка 15 мин"));
        assertTrue(plan.getBlocks().get("evening").contains("Личное время 20:00"));
        assertEquals(2, plan.getBlocks().get("work").size());
        assertTrue(plan.getTasksForDay().isEmpty());
    }

    @Test
    void testGeneratePlanLimitsTasksToTopFiveAndUpdatesExistingSavedPlan() throws Exception {
        // Given
        String userId = "busyUser";
        LocalDate date = LocalDate.now();
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Task t = new Task();
            t.setId((long) i);
            t.setTitle("Task " + i);
            t.setPriority(TaskPriority.MEDIUM);
            tasks.add(t);
        }

        DailyPlan existing = new DailyPlan();
        existing.setUserId(userId);
        existing.setPlanDate(date);
        existing.setPlanJson("{\"old\":true}");

        when(taskRepository.findActiveTasks(userId)).thenReturn(tasks);
        when(userProfileClient.getPlanningContext(userId)).thenReturn(UserProfileClient.PlanningContext.empty());
        when(objectMapper.writeValueAsString(any(DailyPlanDto.class))).thenReturn("{\"new\":true}");
        when(dailyPlanRepository.findByUserIdAndPlanDate(userId, date)).thenReturn(Optional.of(existing));

        // When
        DailyPlanDto plan = generator.generatePlan(userId, date);

        // Then
        assertEquals(5, plan.getTasksForDay().size());
        verify(dailyPlanRepository).save(existing);
        assertEquals("{\"new\":true}", existing.getPlanJson());
    }

    @Test
    void testGeneratePlanSwallowsJsonProcessingExceptionWhileSaving() throws Exception {
        // Given
        String userId = "brokenUser";
        LocalDate date = LocalDate.now();

        when(taskRepository.findActiveTasks(userId)).thenReturn(new ArrayList<>());
        when(userProfileClient.getPlanningContext(userId)).thenReturn(UserProfileClient.PlanningContext.empty());
        when(objectMapper.writeValueAsString(any(DailyPlanDto.class)))
                .thenThrow(new JsonProcessingException("boom") {});

        // When / Then - the checked exception must not propagate out of generatePlan
        DailyPlanDto plan = assertDoesNotThrow(() -> generator.generatePlan(userId, date));
        assertNotNull(plan);
        verify(dailyPlanRepository, never()).save(any(DailyPlan.class));
    }
}
