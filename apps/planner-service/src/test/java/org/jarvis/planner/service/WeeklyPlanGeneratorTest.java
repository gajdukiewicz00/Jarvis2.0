package org.jarvis.planner.service;

import org.jarvis.planner.model.Task;
import org.jarvis.planner.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeeklyPlanGeneratorTest {

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private WeeklyPlanGenerator generator;

    @Test
    void generateWeeklyPlanReturnsMetadataAndSequentialDayDistribution() {
        String userId = "user-1";
        when(taskRepository.findActiveTasks(userId)).thenReturn(List.of(
                task("Inbox zero"),
                task("Workout"),
                task("Read")));

        Map<String, Object> weeklyPlan = generator.generateWeeklyPlan(userId);
        Map<String, List<String>> days = dayPlans(weeklyPlan);

        assertEquals(userId, weeklyPlan.get("userId"));
        assertEquals(LocalDate.now(), weeklyPlan.get("weekStart"));
        assertEquals(3, weeklyPlan.get("totalTasks"));
        assertEquals(List.of("Inbox zero"), days.get("monday"));
        assertEquals(List.of("Workout"), days.get("tuesday"));
        assertEquals(List.of("Read"), days.get("wednesday"));
        verify(taskRepository).findActiveTasks(userId);
    }

    @Test
    void generateWeeklyPlanCurrentlyDropsRemainderTasksBeyondSevenSingleTaskSlots() {
        String userId = "user-2";
        when(taskRepository.findActiveTasks(userId)).thenReturn(List.of(
                task("task-1"),
                task("task-2"),
                task("task-3"),
                task("task-4"),
                task("task-5"),
                task("task-6"),
                task("task-7"),
                task("task-8")));

        Map<String, Object> weeklyPlan = generator.generateWeeklyPlan(userId);
        List<String> scheduledTitles = dayPlans(weeklyPlan).values().stream()
                .flatMap(List::stream)
                .toList();

        assertEquals(8, weeklyPlan.get("totalTasks"));
        assertEquals(7, scheduledTitles.size());
        assertFalse(scheduledTitles.contains("task-8"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> dayPlans(Map<String, Object> weeklyPlan) {
        return (Map<String, List<String>>) weeklyPlan.get("days");
    }

    private Task task(String title) {
        Task task = new Task();
        task.setTitle(title);
        return task;
    }
}
