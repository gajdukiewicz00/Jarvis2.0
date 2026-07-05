package org.jarvis.planner.service;

import org.jarvis.planner.metrics.PlannerMetrics;
import org.jarvis.planner.model.RecurrenceRule;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringTaskGeneratorTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private PlannerMetrics plannerMetrics;

    @InjectMocks
    private RecurringTaskGenerator generator;

    private Task template(RecurrenceRule rule, LocalDate anchor) {
        Task t = new Task();
        t.setId(1L);
        t.setUserId("user-1");
        t.setTitle("Morning review");
        t.setPriority(TaskPriority.MEDIUM);
        t.setEstimatedDuration(20);
        t.setRecurrenceRule(rule);
        t.setRecurrenceAnchorDate(anchor);
        return t;
    }

    @Test
    void dailyTemplateIsDueEveryDateOnOrAfterAnchor() {
        Task daily = template(RecurrenceRule.DAILY, LocalDate.of(2026, 6, 1));

        assertThat(generator.isDue(daily, LocalDate.of(2026, 6, 1))).isTrue();
        assertThat(generator.isDue(daily, LocalDate.of(2026, 6, 15))).isTrue();
        assertThat(generator.isDue(daily, LocalDate.of(2026, 5, 31))).isFalse(); // before anchor
    }

    @Test
    void weeklyTemplateIsDueOnlyOnMatchingDayOfWeek() {
        // 2026-06-01 is a Monday
        Task weekly = template(RecurrenceRule.WEEKLY, LocalDate.of(2026, 6, 1));

        assertThat(generator.isDue(weekly, LocalDate.of(2026, 6, 8))).isTrue(); // next Monday
        assertThat(generator.isDue(weekly, LocalDate.of(2026, 6, 9))).isFalse(); // Tuesday
    }

    @Test
    void intervalTemplateIsDueEveryNDaysFromAnchor() {
        Task everyThreeDays = template(RecurrenceRule.INTERVAL, LocalDate.of(2026, 6, 1));
        everyThreeDays.setRecurrenceIntervalDays(3);

        assertThat(generator.isDue(everyThreeDays, LocalDate.of(2026, 6, 1))).isTrue();
        assertThat(generator.isDue(everyThreeDays, LocalDate.of(2026, 6, 4))).isTrue();
        assertThat(generator.isDue(everyThreeDays, LocalDate.of(2026, 6, 3))).isFalse();
    }

    @Test
    void intervalTemplateWithoutIntervalDaysDefaultsToDaily() {
        Task noInterval = template(RecurrenceRule.INTERVAL, LocalDate.of(2026, 6, 1));
        noInterval.setRecurrenceIntervalDays(null);

        assertThat(generator.isDue(noInterval, LocalDate.of(2026, 6, 2))).isTrue();
    }

    @Test
    void noneRuleIsNeverDue() {
        Task none = template(RecurrenceRule.NONE, LocalDate.of(2026, 6, 1));

        assertThat(generator.isDue(none, LocalDate.of(2026, 6, 1))).isFalse();
    }

    @Test
    void generateOccurrencesForDateCreatesOccurrenceAndStampsTemplateLastGeneratedDate() {
        LocalDate date = LocalDate.of(2026, 6, 1);
        Task daily = template(RecurrenceRule.DAILY, date);
        daily.setTags(List.of("focus"));
        when(taskRepository.findByUserIdAndRecurrenceRuleNot("user-1", RecurrenceRule.NONE))
                .thenReturn(List.of(daily));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Task> generated = generator.generateOccurrencesForDate("user-1", date);

        assertThat(generated).hasSize(1);
        Task occurrence = generated.get(0);
        assertThat(occurrence.getTitle()).isEqualTo("Morning review");
        assertThat(occurrence.getUserId()).isEqualTo("user-1");
        assertThat(occurrence.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(occurrence.getRecurrenceRule()).isEqualTo(RecurrenceRule.NONE);
        assertThat(occurrence.getRecurrenceSourceTaskId()).isEqualTo(1L);
        assertThat(occurrence.getTags()).containsExactly("focus");
        assertThat(daily.getLastGeneratedDate()).isEqualTo(date);
        verify(taskRepository, times(2)).save(any(Task.class)); // occurrence + template stamp
        verify(plannerMetrics).recurringTaskGenerated("DAILY");
    }

    @Test
    void generateOccurrencesForDateSkipsTemplateAlreadyGeneratedForThatDate() {
        LocalDate date = LocalDate.of(2026, 6, 1);
        Task daily = template(RecurrenceRule.DAILY, date);
        daily.setLastGeneratedDate(date);
        when(taskRepository.findByUserIdAndRecurrenceRuleNot("user-1", RecurrenceRule.NONE))
                .thenReturn(List.of(daily));

        List<Task> generated = generator.generateOccurrencesForDate("user-1", date);

        assertThat(generated).isEmpty();
        verify(taskRepository, never()).save(any(Task.class));
        verify(plannerMetrics, never()).recurringTaskGenerated(any());
    }

    @Test
    void generateOccurrencesForDateSkipsTemplatesNotDueOnThatDate() {
        LocalDate anchor = LocalDate.of(2026, 6, 1); // Monday
        Task weekly = template(RecurrenceRule.WEEKLY, anchor);
        when(taskRepository.findByUserIdAndRecurrenceRuleNot("user-1", RecurrenceRule.NONE))
                .thenReturn(List.of(weekly));

        List<Task> generated = generator.generateOccurrencesForDate("user-1", LocalDate.of(2026, 6, 2));

        assertThat(generated).isEmpty();
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    void generateOccurrencesForDateReturnsEmptyWhenNoTemplates() {
        when(taskRepository.findByUserIdAndRecurrenceRuleNot("user-1", RecurrenceRule.NONE))
                .thenReturn(List.of());

        List<Task> generated = generator.generateOccurrencesForDate("user-1", LocalDate.now());

        assertThat(generated).isEmpty();
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    void generatedOccurrenceDueDateCombinesTemplateTimeOfDayWithTargetDate() {
        LocalDate date = LocalDate.of(2026, 6, 1);
        Task weekly = template(RecurrenceRule.WEEKLY, date);
        weekly.setDueDate(java.time.Instant.parse("2026-05-25T08:30:00Z"));
        when(taskRepository.findByUserIdAndRecurrenceRuleNot("user-1", RecurrenceRule.NONE))
                .thenReturn(List.of(weekly));
        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        when(taskRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        generator.generateOccurrencesForDate("user-1", date);

        Task savedOccurrence = captor.getAllValues().get(0);
        assertThat(savedOccurrence.getDueDate()).isEqualTo(java.time.Instant.parse("2026-06-01T08:30:00Z"));
    }

    @Test
    void generateOccurrencesForDateRecordsOneMetricPerGeneratedOccurrenceTaggedByRule() {
        LocalDate date = LocalDate.of(2026, 6, 1);
        Task daily = template(RecurrenceRule.DAILY, date);
        Task weekly = template(RecurrenceRule.WEEKLY, date);
        weekly.setId(2L);
        when(taskRepository.findByUserIdAndRecurrenceRuleNot("user-1", RecurrenceRule.NONE))
                .thenReturn(List.of(daily, weekly));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Task> generated = generator.generateOccurrencesForDate("user-1", date);

        assertThat(generated).hasSize(2);
        verify(plannerMetrics).recurringTaskGenerated("DAILY");
        verify(plannerMetrics).recurringTaskGenerated("WEEKLY");
    }
}
