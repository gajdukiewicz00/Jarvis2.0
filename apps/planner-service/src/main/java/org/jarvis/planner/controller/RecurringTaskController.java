package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.dto.TaskDto;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.service.RecurringOccurrenceService;
import org.jarvis.planner.service.RecurringTaskGenerator;
import org.jarvis.planner.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Recurring-task occurrence lifecycle: skip or complete a single generated
 * occurrence without ending the series (see {@link RecurringOccurrenceService}),
 * and materialize upcoming occurrences ahead of schedule (see
 * {@link RecurringTaskGenerator#generateNextOccurrences}). Shares the
 * {@code /api/v1/planner/tasks} base path with {@link TaskController}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/planner/tasks")
@RequiredArgsConstructor
public class RecurringTaskController {

    private static final int DEFAULT_GENERATE_COUNT = 5;

    private final RecurringOccurrenceService recurringOccurrenceService;
    private final RecurringTaskGenerator recurringTaskGenerator;
    private final TaskService taskService;

    /** Mark one occurrence skipped — the recurring template keeps generating future occurrences. */
    @PatchMapping("/{id}/skip-occurrence")
    public ResponseEntity<TaskDto> skipOccurrence(@PathVariable Long id, Authentication authentication) {
        String userId = authentication.getName();
        log.info("PATCH skip-occurrence for task {} (user {})", id, userId);
        Task occurrence = recurringOccurrenceService.skipOccurrence(userId, id);
        return ResponseEntity.ok(taskService.toDto(occurrence));
    }

    /** Mark one occurrence done — the recurring template keeps generating future occurrences. */
    @PatchMapping("/{id}/complete-occurrence")
    public ResponseEntity<TaskDto> completeOccurrence(@PathVariable Long id, Authentication authentication) {
        String userId = authentication.getName();
        log.info("PATCH complete-occurrence for task {} (user {})", id, userId);
        Task occurrence = recurringOccurrenceService.completeOccurrence(userId, id);
        return ResponseEntity.ok(taskService.toDto(occurrence));
    }

    /** Materialize the next {@code count} due occurrences of a recurring template into tasks. */
    @PostMapping("/{id}/generate-next-occurrences")
    public ResponseEntity<List<TaskDto>> generateNextOccurrences(
            @PathVariable Long id,
            @RequestParam(defaultValue = "" + DEFAULT_GENERATE_COUNT) int count,
            Authentication authentication) {
        String userId = authentication.getName();
        log.info("POST generate-next-occurrences for template {} count={} (user {})", id, count, userId);
        List<Task> generated = recurringTaskGenerator.generateNextOccurrences(userId, id, count);
        return ResponseEntity.ok(generated.stream().map(taskService::toDto).toList());
    }
}
