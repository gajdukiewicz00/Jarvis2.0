package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.exception.TaskNotFoundException;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Per-occurrence lifecycle for recurring tasks (RRULE-lite): skip or complete
 * a single generated occurrence without ending the series. Each occurrence is
 * its own {@link Task} row (see {@link RecurringTaskGenerator}), independent
 * of its recurring template, so mutating one never touches the template's own
 * recurrence fields or future generation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringOccurrenceService {

    private final TaskRepository taskRepository;

    /** Mark one occurrence as skipped — the series keeps generating future occurrences unaffected. */
    @Transactional
    public Task skipOccurrence(String userId, Long occurrenceId) {
        Task occurrence = requireOccurrence(userId, occurrenceId);
        occurrence.setStatus(TaskStatus.SKIPPED);
        occurrence.setSkippedAt(Instant.now());
        occurrence.setUpdatedBy(userId);
        Task saved = taskRepository.save(occurrence);
        log.info("Skipped occurrence {} (template {}) for user {}",
                occurrenceId, saved.getRecurrenceSourceTaskId(), userId);
        return saved;
    }

    /** Mark one occurrence as done — the series keeps generating future occurrences unaffected. */
    @Transactional
    public Task completeOccurrence(String userId, Long occurrenceId) {
        Task occurrence = requireOccurrence(userId, occurrenceId);
        occurrence.setStatus(TaskStatus.DONE);
        occurrence.setCompletedAt(Instant.now());
        occurrence.setUpdatedBy(userId);
        Task saved = taskRepository.save(occurrence);
        log.info("Completed occurrence {} (template {}) for user {}",
                occurrenceId, saved.getRecurrenceSourceTaskId(), userId);
        return saved;
    }

    private Task requireOccurrence(String userId, Long occurrenceId) {
        Task task = taskRepository.findByIdAndUserId(occurrenceId, userId)
                .orElseThrow(() -> new TaskNotFoundException(occurrenceId, userId));
        if (task.getRecurrenceSourceTaskId() == null) {
            throw new IllegalArgumentException("Task " + occurrenceId + " is not a recurring occurrence");
        }
        return task;
    }
}
