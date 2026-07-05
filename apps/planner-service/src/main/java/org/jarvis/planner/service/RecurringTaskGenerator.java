package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            if (date.equals(template.getLastGeneratedDate()) || !isDue(template, date)) {
                continue;
            }
            Task occurrence = taskRepository.save(buildOccurrence(template, date));
            generated.add(occurrence);
            plannerMetrics.recurringTaskGenerated(template.getRecurrenceRule().name());

            template.setLastGeneratedDate(date);
            taskRepository.save(template);
        }

        log.info("Generated {} recurring task occurrence(s) for user {} on {}", generated.size(), userId, date);
        return generated;
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
