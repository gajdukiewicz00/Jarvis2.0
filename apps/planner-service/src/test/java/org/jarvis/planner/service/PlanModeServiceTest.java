package org.jarvis.planner.service;

import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.repository.TaskRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanModeServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private EnergyAwareRanker ranker;

    @InjectMocks
    private PlanModeService planModeService;

    private Task task(Long id, String title, TaskPriority priority, Instant dueDate) {
        Task t = new Task();
        t.setId(id);
        t.setTitle(title);
        t.setPriority(priority);
        t.setDueDate(dueDate);
        t.setEstimatedDuration(30);
        return t;
    }

    @Test
    void minimumViableDayKeepsOnlyUrgentAndOverdueTasksCappedAtThree() {
        Task urgent = task(1L, "Urgent thing", TaskPriority.URGENT, null);
        Task overdue = task(2L, "Overdue thing", TaskPriority.MEDIUM, Instant.now().minus(1, ChronoUnit.DAYS));
        Task routine = task(3L, "Routine thing", TaskPriority.LOW, null);
        List<Task> active = List.of(urgent, overdue, routine);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(active);
        when(ranker.rank(active, EnergyLevel.NORMAL, false)).thenReturn(List.of(urgent, overdue, routine));

        Map<String, Object> result = planModeService.minimumViableDay("user-1", EnergyLevel.NORMAL);

        assertThat(result.get("taskCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("tasks");
        assertThat(tasks).extracting(m -> m.get("taskId")).containsExactly(1L, 2L);
    }

    @Test
    void minimumViableDayFallsBackToTopRankedTaskWhenNothingIsEssential() {
        Task routine = task(1L, "Routine thing", TaskPriority.LOW, null);
        List<Task> active = List.of(routine);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(active);
        when(ranker.rank(active, EnergyLevel.NORMAL, false)).thenReturn(List.of(routine));

        Map<String, Object> result = planModeService.minimumViableDay("user-1", EnergyLevel.NORMAL);

        assertThat(result.get("taskCount")).isEqualTo(1);
    }

    @Test
    void minimumViableDayWithNoTasksReportsEmptyDay() {
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of());
        when(ranker.rank(List.of(), EnergyLevel.NORMAL, false)).thenReturn(List.of());

        Map<String, Object> result = planModeService.minimumViableDay("user-1", EnergyLevel.NORMAL);

        assertThat(result.get("taskCount")).isEqualTo(0);
        assertThat(result.get("message")).isEqualTo("Открытых задач нет, сэр — минимальный день пуст, можно отдыхать.");
    }

    @Test
    void deepWorkBlockPicksTheFirstHardTaskFromTheRankedList() {
        Task light = task(1L, "Light thing", TaskPriority.LOW, null);
        Task hard = task(2L, "Big refactor", TaskPriority.HIGH, null);
        hard.setEstimatedDuration(120);
        List<Task> active = List.of(light, hard);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(active);
        when(ranker.rank(active, EnergyLevel.HIGH, false)).thenReturn(List.of(hard, light));
        when(ranker.isHard(hard)).thenReturn(true);

        Map<String, Object> result = planModeService.deepWorkBlock("user-1", EnergyLevel.HIGH);

        assertThat(result.get("hasBlock")).isEqualTo(true);
        assertThat(result.get("taskId")).isEqualTo(2L);
        assertThat(result.get("blockMinutes")).isEqualTo(120);
    }

    @Test
    void deepWorkBlockRefusesWhenEnergyIsExhausted() {
        Map<String, Object> result = planModeService.deepWorkBlock("user-1", EnergyLevel.EXHAUSTED);

        assertThat(result.get("hasBlock")).isEqualTo(false);
        assertThat(result.get("message")).isEqualTo("Ты вымотан, сэр — не время для глубокой работы. Лучше отдохнуть.");
    }

    @Test
    void deepWorkBlockReportsNoBlockWhenNoHardTasksExist() {
        Task light = task(1L, "Light thing", TaskPriority.LOW, null);
        List<Task> active = List.of(light);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(active);
        when(ranker.rank(active, EnergyLevel.NORMAL, false)).thenReturn(List.of(light));
        when(ranker.isHard(light)).thenReturn(false);

        Map<String, Object> result = planModeService.deepWorkBlock("user-1", EnergyLevel.NORMAL);

        assertThat(result.get("hasBlock")).isEqualTo(false);
    }

    @Test
    void deepWorkBlockUsesMinimumSessionLengthWhenTaskEstimateIsShorter() {
        Task hard = task(1L, "Deep task", TaskPriority.HIGH, null);
        hard.setEstimatedDuration(45); // shorter than the deep-work minimum
        List<Task> active = List.of(hard);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(active);
        when(ranker.rank(active, EnergyLevel.NORMAL, false)).thenReturn(List.of(hard));
        when(ranker.isHard(hard)).thenReturn(true);

        Map<String, Object> result = planModeService.deepWorkBlock("user-1", EnergyLevel.NORMAL);

        assertThat(result.get("blockMinutes")).isEqualTo(PlanModeService.DEEP_WORK_MIN_MINUTES);
    }
}
