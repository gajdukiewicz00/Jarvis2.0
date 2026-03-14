package org.jarvis.planner.service;

import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleOptimizerTest {

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private ScheduleOptimizer optimizer;

    @Test
    void optimizeScheduleSortsByPriorityThenDueDateAndDistributesAcrossDays() {
        String userId = "user-1";
        LocalDate startDate = LocalDate.of(2026, 3, 9);
        Task low = task("low", TaskPriority.LOW, null, 10);
        Task highLate = task("high-late", TaskPriority.HIGH, Instant.parse("2026-03-11T09:00:00Z"), 20);
        Task urgent = task("urgent", TaskPriority.URGENT, Instant.parse("2026-03-12T09:00:00Z"), 30);
        Task highEarly = task("high-early", TaskPriority.HIGH, Instant.parse("2026-03-10T09:00:00Z"), 40);

        when(taskRepository.findActiveTasks(userId)).thenReturn(List.of(low, highLate, urgent, highEarly));

        Map<String, List<Task>> schedule = optimizer.optimizeSchedule(userId, startDate, 2);

        assertEquals(List.of(urgent, highEarly), schedule.get("2026-03-09"));
        assertEquals(List.of(highLate, low), schedule.get("2026-03-10"));
        verify(taskRepository).findActiveTasks(userId);
    }

    @Test
    void optimizeScheduleCurrentlyDropsRemainderTasksWhenEvenSplitDoesNotFit() {
        String userId = "user-2";
        LocalDate startDate = LocalDate.of(2026, 3, 9);
        Task first = task("first", TaskPriority.HIGH, Instant.parse("2026-03-09T09:00:00Z"), null);
        Task second = task("second", TaskPriority.MEDIUM, Instant.parse("2026-03-10T09:00:00Z"), null);
        Task third = task("third", TaskPriority.LOW, Instant.parse("2026-03-11T09:00:00Z"), null);

        when(taskRepository.findActiveTasks(userId)).thenReturn(List.of(first, second, third));

        Map<String, List<Task>> schedule = optimizer.optimizeSchedule(userId, startDate, 2);
        List<String> scheduledTitles = schedule.values().stream()
                .flatMap(List::stream)
                .map(Task::getTitle)
                .toList();

        assertEquals(2, scheduledTitles.size());
        assertFalse(scheduledTitles.contains("third"));
    }

    @Test
    void calculateTotalDurationSumsOnlyDefinedDurations() {
        int total = optimizer.calculateTotalDuration(List.of(
                task("a", TaskPriority.LOW, null, 15),
                task("b", TaskPriority.MEDIUM, null, null),
                task("c", TaskPriority.HIGH, null, 45)));

        assertEquals(60, total);
    }

    private Task task(String title, TaskPriority priority, Instant dueDate, Integer duration) {
        Task task = new Task();
        task.setTitle(title);
        task.setPriority(priority);
        task.setDueDate(dueDate);
        task.setEstimatedDuration(duration);
        return task;
    }
}
