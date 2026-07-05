package org.jarvis.planner.service;

import org.jarvis.planner.exception.TaskNotFoundException;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringOccurrenceServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private RecurringOccurrenceService recurringOccurrenceService;

    private Task occurrence(Long templateId) {
        Task t = new Task();
        t.setId(5L);
        t.setUserId("user-1");
        t.setTitle("Morning review");
        t.setStatus(TaskStatus.TODO);
        t.setRecurrenceSourceTaskId(templateId);
        return t;
    }

    @Test
    void skipOccurrenceMarksSkippedAndStampsSkippedAtWithoutTouchingTemplate() {
        Task occurrence = occurrence(1L);
        when(taskRepository.findByIdAndUserId(5L, "user-1")).thenReturn(Optional.of(occurrence));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Task result = recurringOccurrenceService.skipOccurrence("user-1", 5L);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.SKIPPED);
        assertThat(result.getSkippedAt()).isNotNull();
        assertThat(result.getUpdatedBy()).isEqualTo("user-1");
        assertThat(result.getRecurrenceSourceTaskId()).isEqualTo(1L); // template link preserved, template untouched
        verify(taskRepository).save(occurrence);
    }

    @Test
    void completeOccurrenceMarksDoneAndStampsCompletedAtWithoutTouchingTemplate() {
        Task occurrence = occurrence(1L);
        when(taskRepository.findByIdAndUserId(5L, "user-1")).thenReturn(Optional.of(occurrence));
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));

        Task result = recurringOccurrenceService.completeOccurrence("user-1", 5L);

        assertThat(result.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(result.getCompletedAt()).isNotNull();
        assertThat(result.getUpdatedBy()).isEqualTo("user-1");
        assertThat(result.getRecurrenceSourceTaskId()).isEqualTo(1L);
    }

    @Test
    void skipOccurrenceThrowsWhenTaskNotFound() {
        when(taskRepository.findByIdAndUserId(99L, "user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recurringOccurrenceService.skipOccurrence("user-1", 99L))
                .isInstanceOf(TaskNotFoundException.class);
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    void completeOccurrenceThrowsWhenTaskIsNotARecurringOccurrence() {
        Task plain = new Task();
        plain.setId(6L);
        plain.setUserId("user-1");
        plain.setRecurrenceSourceTaskId(null); // one-off task, not a generated occurrence
        when(taskRepository.findByIdAndUserId(6L, "user-1")).thenReturn(Optional.of(plain));

        assertThatThrownBy(() -> recurringOccurrenceService.completeOccurrence("user-1", 6L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a recurring occurrence");
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    void skipOccurrenceThrowsWhenTaskIsNotARecurringOccurrence() {
        Task plain = new Task();
        plain.setId(6L);
        plain.setUserId("user-1");
        plain.setRecurrenceSourceTaskId(null);
        when(taskRepository.findByIdAndUserId(6L, "user-1")).thenReturn(Optional.of(plain));

        assertThatThrownBy(() -> recurringOccurrenceService.skipOccurrence("user-1", 6L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a recurring occurrence");
    }
}
