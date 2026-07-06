package org.jarvis.planner.service;

import org.jarvis.planner.client.WellnessClient;
import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.PlanMode;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.model.WellnessSignal;
import org.jarvis.planner.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanAdjustmentServiceTest {

    @Mock
    private WellnessClient wellnessClient;

    @Mock
    private EnergyStateService energyStateService;

    @Mock
    private EnergyAwareRanker ranker;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private RescheduleService rescheduleService;

    @InjectMocks
    private PlanAdjustmentService planAdjustmentService;

    private Task task(Long id, String title) {
        Task t = new Task();
        t.setId(id);
        t.setTitle(title);
        t.setPriority(TaskPriority.MEDIUM);
        t.setEstimatedDuration(45);
        return t;
    }

    // --- signal -> mode mapping -------------------------------------------------

    @Test
    void veryLowSleepAlwaysSuggestsRecoveryEvenWithHighEnergy() {
        WellnessSignal signal = new WellnessSignal(4.0, 12000, EnergyLevel.HIGH);

        PlanMode mode = planAdjustmentService.suggestMode(signal);

        assertThat(mode).isEqualTo(PlanMode.RECOVERY);
    }

    @Test
    void exhaustedEnergySuggestsRecovery() {
        WellnessSignal signal = new WellnessSignal(7.5, 5000, EnergyLevel.EXHAUSTED);

        PlanMode mode = planAdjustmentService.suggestMode(signal);

        assertThat(mode).isEqualTo(PlanMode.RECOVERY);
    }

    @Test
    void lowEnergyCompoundedByShortSleepSuggestsRecovery() {
        WellnessSignal signal = new WellnessSignal(6.0, 3000, EnergyLevel.LOW);

        PlanMode mode = planAdjustmentService.suggestMode(signal);

        assertThat(mode).isEqualTo(PlanMode.RECOVERY);
    }

    @Test
    void lowEnergyWithAdequateSleepDoesNotForceRecovery() {
        WellnessSignal signal = new WellnessSignal(7.5, 3000, EnergyLevel.LOW);

        PlanMode mode = planAdjustmentService.suggestMode(signal);

        assertThat(mode).isEqualTo(PlanMode.NORMAL);
    }

    @Test
    void highEnergySuggestsDeepWork() {
        WellnessSignal signal = new WellnessSignal(8.0, 9000, EnergyLevel.HIGH);

        PlanMode mode = planAdjustmentService.suggestMode(signal);

        assertThat(mode).isEqualTo(PlanMode.DEEP_WORK);
    }

    @Test
    void normalSignalSuggestsNormalMode() {
        WellnessSignal signal = new WellnessSignal(7.5, 6000, EnergyLevel.NORMAL);

        PlanMode mode = planAdjustmentService.suggestMode(signal);

        assertThat(mode).isEqualTo(PlanMode.NORMAL);
    }

    @Test
    void nullSignalSuggestsNormalMode() {
        assertThat(planAdjustmentService.suggestMode(null)).isEqualTo(PlanMode.NORMAL);
    }

    // --- graceful fallback -------------------------------------------------

    @Test
    void adjustedPlanFallsBackToNeutralSignalWhenClientThrows() {
        when(wellnessClient.fetchSignal("user-1")).thenThrow(new RuntimeException("life-tracker unreachable"));
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.NORMAL);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of());
        when(ranker.rank(List.of(), EnergyLevel.NORMAL, false, PlanMode.NORMAL)).thenReturn(List.of());

        Map<String, Object> result = planAdjustmentService.getAdjustedPlan("user-1", false);

        assertThat(result.get("mode")).isEqualTo("NORMAL");
        @SuppressWarnings("unchecked")
        Map<String, Object> wellness = (Map<String, Object>) result.get("wellness");
        assertThat(wellness.get("sleepHours")).isNull();
        assertThat(wellness.get("steps")).isNull();
        assertThat(wellness.get("energy")).isEqualTo("NORMAL");
        verify(rescheduleService, never()).rescheduleWhenTired(anyString(), anyBoolean());
    }

    // --- adjusted-plan explanation & reschedule reuse -------------------------------------------------

    @Test
    void adjustedPlanExplainsRecoveryModeAndReusesRescheduleWhenTired() {
        WellnessSignal signal = new WellnessSignal(4.0, 1000, EnergyLevel.EXHAUSTED);
        Task hard = task(1L, "Big refactor");
        when(wellnessClient.fetchSignal("user-1")).thenReturn(signal);
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.EXHAUSTED);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(hard));
        when(ranker.rank(List.of(hard), EnergyLevel.EXHAUSTED, false, PlanMode.RECOVERY)).thenReturn(List.of(hard));
        when(ranker.deadlineLabel(hard)).thenReturn("NONE");
        when(rescheduleService.rescheduleWhenTired("user-1", false)).thenReturn(Map.of("rescheduled", true));

        Map<String, Object> result = planAdjustmentService.getAdjustedPlan("user-1", false);

        assertThat(result.get("mode")).isEqualTo("RECOVERY");
        assertThat((String) result.get("explanation")).contains("восстановления");
        assertThat(result.get("rescheduleRecommended")).isEqualTo(true);
        assertThat(result.get("reschedule")).isEqualTo(Map.of("rescheduled", true));
        verify(rescheduleService).rescheduleWhenTired("user-1", false);
    }

    @Test
    void adjustedPlanExplainsDeepWorkModeAndSkipsReschedule() {
        WellnessSignal signal = new WellnessSignal(8.0, 10000, EnergyLevel.HIGH);
        when(wellnessClient.fetchSignal("user-1")).thenReturn(signal);
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.HIGH);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of());
        when(ranker.rank(List.of(), EnergyLevel.HIGH, false, PlanMode.DEEP_WORK)).thenReturn(List.of());

        Map<String, Object> result = planAdjustmentService.getAdjustedPlan("user-1", false);

        assertThat(result.get("mode")).isEqualTo("DEEP_WORK");
        assertThat((String) result.get("explanation")).contains("глубокой работы");
        assertThat(result.get("rescheduleRecommended")).isEqualTo(false);
        assertThat(result).doesNotContainKey("reschedule");
        verify(rescheduleService, never()).rescheduleWhenTired(anyString(), anyBoolean());
    }

    @Test
    void adjustedPlanIncludesRankedTaskSummaries() {
        WellnessSignal signal = new WellnessSignal(7.0, 5000, EnergyLevel.NORMAL);
        Task t = task(9L, "Write report");
        when(wellnessClient.fetchSignal("user-1")).thenReturn(signal);
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.NORMAL);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of(t));
        when(ranker.rank(List.of(t), EnergyLevel.NORMAL, false, PlanMode.NORMAL)).thenReturn(List.of(t));
        when(ranker.deadlineLabel(t)).thenReturn("NONE");

        Map<String, Object> result = planAdjustmentService.getAdjustedPlan("user-1", false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("tasks");
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("taskId")).isEqualTo(9L);
        assertThat(tasks.get(0).get("title")).isEqualTo("Write report");
    }

    @Test
    void adjustedPlanForwardsForceFlagToRankerAndReschedule() {
        WellnessSignal signal = new WellnessSignal(4.0, 500, EnergyLevel.EXHAUSTED);
        when(wellnessClient.fetchSignal("user-1")).thenReturn(signal);
        when(energyStateService.get("user-1")).thenReturn(EnergyLevel.NORMAL);
        when(taskRepository.findActiveTasks("user-1")).thenReturn(List.of());
        when(ranker.rank(List.of(), EnergyLevel.NORMAL, true, PlanMode.RECOVERY)).thenReturn(List.of());
        when(rescheduleService.rescheduleWhenTired("user-1", true)).thenReturn(Map.of("rescheduled", true));

        planAdjustmentService.getAdjustedPlan("user-1", true);

        verify(ranker).rank(any(), any(), eq(true), any());
        verify(rescheduleService).rescheduleWhenTired("user-1", true);
    }
}
