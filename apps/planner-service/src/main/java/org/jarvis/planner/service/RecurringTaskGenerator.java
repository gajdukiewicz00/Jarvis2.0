package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.exception.TaskNotFoundException;
import org.jarvis.planner.metrics.PlannerMetrics;
import org.jarvis.planner.model.RecurrenceRule;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskStatus;
import org.jarvis.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * RRULE-lite recurring task generation. DAILY / WEEKLY / INTERVAL templates
 * spawn a concrete occurrence task for a given calendar date, once per date,
 * so re-running generation for the same date never double-books it. Hooked
 * into {@link DailyPlanGenerator} so a day's plan always includes today's
 * recurring occurrences before active tasks are gathered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringTaskGenerator {

    private static final LocalTime DEFAULT_OCCURRENCE_TIME = LocalTime.of(23, 59);

    /** Upper bound on how many occurrences a single generate-next-occurrences call may materialize. */
    static final int MAX_GENERATE_NEXT_COUNT = 60;

    /** Safety cap on how many calendar days to scan looking for due dates (guards against a runaway loop). */
    private static final int MAX_SCAN_DAYS = 3660;

    private final TaskRepository taskRepository;
    private final PlannerMetrics plannerMetrics;

    /**
     * Generate any recurring occurrences due for {@code date}, for templates
     * that have not already been generated for that date.
     */
    @Transactional
    public List<Task> generateOccurrencesForDate(String userId, LocalDate date) {
        List<Task> templates = taskRepository.findByUserIdAndRecurrenceRuleNot(userId, RecurrenceRule.NONE);
        List<Task> generated = new ArrayList<>();

        for (Task template : templates) {
            if (!isDue(template, date) || occurrenceAlreadyGenerated(template, date)) {
                continue;
            }
            Task occurrence = taskRepository.save(buildOccurrence(template, date));
            generated.add(occurrence);
            plannerMetrics.recurringTaskGenerated(template.getRecurrenceRule().name());

            if (template.getLastGeneratedDate() == null || date.isAfter(template.getLastGeneratedDate())) {
                template.setLastGeneratedDate(date);
                taskRepository.save(template);
            }
        }

        log.info("Generated {} recurring task occurrence(s) for user {} on {}", generated.size(), userId, date);
        return generated;
    }

    /**
     * Materialize the next {@code count} due occurrences of a single recurring
     * template into concrete tasks, scanning forward from the day after its
     * {@code lastGeneratedDate} (or its anchor date, if none generated yet).
     * Advances the template's {@code lastGeneratedDate} as it goes, so a
     * subsequent daily {@link #generateOccurrencesForDate} pass never
     * double-books a date this call already materialized.
     */
    @Transactional
    public List<Task> generateNextOccurrences(String userId, Long templateId, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        if (count > MAX_GENERATE_NEXT_COUNT) {
            throw new IllegalArgumentException("count must not exceed " + MAX_GENERATE_NEXT_COUNT);
        }
        Task template = taskRepository.findByIdAndUserId(templateId, userId)
                .orElseThrow(() -> new TaskNotFoundException(templateId, userId));
        if (template.getRecurrenceRule() == RecurrenceRule.NONE) {
            throw new IllegalArgumentException("Task " + templateId + " is not a recurring template");
        }

        List<Task> generated = new ArrayList<>();
        LocalDate cursor = template.getLastGeneratedDate() != null
                ? template.getLastGeneratedDate().plusDays(1)
                : anchorDate(template, LocalDate.now());

        int scanned = 0;
        while (generated.size() < count && scanned < MAX_SCAN_DAYS) {
            if (isDue(template, cursor)) {
                Task occurrence = taskRepository.save(buildOccurrence(template, cursor));
                generated.add(occurrence);
                plannerMetrics.recurringTaskGenerated(template.getRecurrenceRule().name());
                template.setLastGeneratedDate(cursor);
            }
            cursor = cursor.plusDays(1);
            scanned++;
        }
        taskRepository.save(template);

        log.info("Generated next {} occurrence(s) for template {} (user {})", generated.size(), templateId, userId);
        return generated;
    }

    /**
     * Whether {@code template} already has a materialized occurrence for
     * {@code date}. Checked against the actual persisted occurrences rather
     * than the scalar {@code lastGeneratedDate}, since a prior
     * {@link #generateNextOccurrences} batch can advance that value past
     * {@code date} while still having generated {@code date}'s occurrence as
     * part of the same batch.
     */
    private boolean occurrenceAlreadyGenerated(Task template, LocalDate date) {
        Instant startOfDay = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endOfDay = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return taskRepository.existsByRecurrenceSourceTaskIdAndDueDateGreaterThanEqualAndDueDateLessThan(
                template.getId(), startOfDay, endOfDay);
    }

    /** Whether {@code template}'s recurrence pattern is due on {@code date}. */
    boolean isDue(Task template, LocalDate date) {
        LocalDate anchor = anchorDate(template, date);
        if (date.isBefore(anchor)) {
            return false;
        }
        return switch (template.getRecurrenceRule()) {
            case DAILY -> true;
            case WEEKLY -> date.getDayOfWeek() == anchor.getDayOfWeek();
            case INTERVAL -> {
                int interval = template.getRecurrenceIntervalDays() == null
                        ? 1 : Math.max(1, template.getRecurrenceIntervalDays());
                yield ChronoUnit.DAYS.between(anchor, date) % interval == 0;
            }
            case NONE -> false;
        };
    }

    private LocalDate anchorDate(Task template, LocalDate fallback) {
        if (template.getRecurrenceAnchorDate() != null) {
            return template.getRecurrenceAnchorDate();
        }
        if (template.getDueDate() != null) {
            return template.getDueDate().atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (template.getCreatedAt() != null) {
            return template.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        }
        return fallback;
    }

    private Task buildOccurrence(Task template, LocalDate date) {
        Task occurrence = new Task();
        occurrence.setUserId(template.getUserId());
        occurrence.setTitle(template.getTitle());
        occurrence.setDescription(template.getDescription());
        occurrence.setCategory(template.getCategory());
        occurrence.setPriority(template.getPriority());
        occurrence.setStatus(TaskStatus.TODO);
        occurrence.setDueDate(occurrenceDueDate(template, date));
        occurrence.setEstimatedDuration(template.getEstimatedDuration());
        occurrence.setTags(template.getTags() == null ? new ArrayList<>() : new ArrayList<>(template.getTags()));
        occurrence.setSource(template.getSource());
        occurrence.setCreatedBy(template.getCreatedBy());
        occurrence.setUpdatedBy(template.getUpdatedBy());
        occurrence.setRecurrenceRule(RecurrenceRule.NONE);
        occurrence.setRecurrenceSourceTaskId(template.getId());
        return occurrence;
    }

    private Instant occurrenceDueDate(Task template, LocalDate date) {
        LocalTime time = template.getDueDate() != null
                ? template.getDueDate().atZone(ZoneOffset.UTC).toLocalTime()
                : DEFAULT_OCCURRENCE_TIME;
        return date.atTime(time).toInstant(ZoneOffset.UTC);
    }
}
