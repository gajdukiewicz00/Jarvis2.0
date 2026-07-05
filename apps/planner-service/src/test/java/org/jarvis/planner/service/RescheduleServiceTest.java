package org.jarvis.planner.service;

import org.jarvis.planner.metrics.PlannerMetrics;
import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RescheduleServiceTest {

    @Mock
    private org.jarvis.planner.repository.TaskRepository taskRepository;

    @Mock
    private EnergyAwareRanker ranker;

    @Mock
    private EnergyStateService energyStateService;

    @Mock
    private PlannerMetrics plannerMetrics;

    @InjectMocks
    private RescheduleService rescheduleService;

    private Task task(Long id, String title, TaskPriority priority, Instant dueDate) {
        Task t = new Task();
        t.setId(id);
        t.setTitle(title);
        t.setPriority(priority);
        t.setDueDate(dueDate);
        return t;
    }

    @Test
    void rescheduleWhenTiredWithExhaustedEnergyDefersHardTasksByOneDay() {
        Instant originalDue = Instant.parse("2026-07-10T09:00:00Z");
        Task hard = task(1L, "Big refactor", TaskPriority.MEDIUM, originalDue);
        Task light = task(2L, "Reply email", TaskPriority.MEDIUM, null);
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.EXHAUSTED);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(hard, light));
        when(ranker.isHard(hard)).thenReturn(true);
        when(ranker.isHard(light)).thenReturn(false);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = rescheduleService.rescheduleWhenTired("user-1", false);

        assertThat(result.get("rescheduled")).isEqualTo(true);
        assertThat(result.get("deferredCount")).isEqualTo(1);
        assertThat(hard.getDueDate()).isEqualTo(originalDue.plus(1, ChronoUnit.DAYS));
        verify(taskRepository).save(hard);
        verify(taskRepository, never()).save(light);
        verify(plannerMetrics).reschedule("exhausted");
        verify(plannerMetrics).deferredTasks(1);
    }

    @Test
    void rescheduleWhenTiredNeverDefersUrgentTasks() {
        Task urgentHard = task(1L, "Urgent big task", TaskPriority.URGENT,
                Instant.parse("2026-07-10T09:00:00Z"));
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.EXHAUSTED);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(urgentHard));
        when(ranker.isHard(urgentHard)).thenReturn(true);

        Map<String, Object> result = rescheduleService.rescheduleWhenTired("user-1", false);

        assertThat(result.get("deferredCount")).isEqualTo(0);
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    void rescheduleWhenTiredNeverDefersAlreadyOverdueTasks() {
        Task overdueHard = task(1L, "Overdue heavy task", TaskPriority.MEDIUM,
                Instant.now().minus(1, ChronoUnit.DAYS));
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.EXHAUSTED);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(overdueHard));
        when(ranker.isHard(overdueHard)).thenReturn(true);

        Map<String, Object> result = rescheduleService.rescheduleWhenTired("user-1", false);

        assertThat(result.get("deferredCount")).isEqualTo(0);
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    void rescheduleWhenTiredWithNormalEnergyAndNoForceDoesNothing() {
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.NORMAL);

        Map<String, Object> result = rescheduleService.rescheduleWhenTired("user-1", false);

        assertThat(result.get("rescheduled")).isEqualTo(false);
        assertThat(result.get("deferredCount")).isEqualTo(0);
        verify(taskRepository, never()).findActiveTasks(any());
        verify(taskRepository, never()).save(any(Task.class));
        verify(plannerMetrics, never()).reschedule(any());
        verify(plannerMetrics, never()).deferredTasks(anyInt());
    }

    @Test
    void rescheduleWhenTiredWithForceDefersEvenWhenEnergyIsNormal() {
        Task hard = task(1L, "Big refactor", TaskPriority.MEDIUM,
                Instant.parse("2026-07-10T09:00:00Z"));
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.NORMAL);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(hard));
        when(ranker.isHard(hard)).thenReturn(true);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = rescheduleService.rescheduleWhenTired("user-1", true);

        assertThat(result.get("rescheduled")).isEqualTo(true);
        assertThat(result.get("deferredCount")).isEqualTo(1);
        verify(plannerMetrics).reschedule("forced");
        verify(plannerMetrics).deferredTasks(1);
    }

    @Test
    void rescheduleWhenTiredWithNoHardTasksReportsNoneDeferred() {
        Task light = task(1L, "Reply email", TaskPriority.LOW, null);
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.EXHAUSTED);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(light));
        when(ranker.isHard(light)).thenReturn(false);

        Map<String, Object> result = rescheduleService.rescheduleWhenTired("user-1", false);

        assertThat(result.get("deferredCount")).isEqualTo(0);
        assertThat(result.get("message")).isEqualTo("Тяжёлых задач для переноса не нашлось, сэр. Можно спокойно отдыхать.");
        verify(plannerMetrics).reschedule("exhausted");
        verify(plannerMetrics).deferredTasks(0);
    }
}
