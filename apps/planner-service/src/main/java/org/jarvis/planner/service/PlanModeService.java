package org.jarvis.planner.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * P1 #10 — alternate daily-plan "modes" for days that don't fit the standard
 * full plan: a minimum-viable slice for overloaded/low-capacity days, and a
 * single deep-work block for days that can afford focused, uninterrupted time.
 */
@Service
@RequiredArgsConstructor
public class PlanModeService {

    static final int MIN_VIABLE_DAY_MAX_TASKS = 3;
    static final int DEEP_WORK_MIN_MINUTES = 90;

    private final TaskRepository taskRepository;
    private final EnergyAwareRanker ranker;

    /** The bare minimum to do today so nothing important slips: urgent/overdue first, capped small. */
    public Map<String, Object> minimumViableDay(String userId, EnergyLevel energy) {
        List<Task> ranked = ranker.rank(taskRepository.findActiveTasks(userId), energy, false);

        List<Task> essential = ranked.stream()
                .filter(this::isEssential)
                .limit(MIN_VIABLE_DAY_MAX_TASKS)
                .toList();
        if (essential.isEmpty() && !ranked.isEmpty()) {
            essential = ranked.subList(0, 1); // guarantee at least the top task when nothing is flagged essential
        }

        int totalMinutes = essential.stream()
                .map(Task::getEstimatedDuration)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "MINIMUM_VIABLE_DAY");
        out.put("energy", energy.name());
        out.put("taskCount", essential.size());
        out.put("estimatedMinutes", totalMinutes);
        out.put("tasks", essential.stream().map(this::summary).toList());
        out.put("message", essential.isEmpty()
                ? "Открытых задач нет, сэр — минимальный день пуст, можно отдыхать."
                : "Минимальный план на сегодня, сэр: " + essential.size() + " задач(и), ~" + totalMinutes + " мин.");
        return out;
    }

    /** A single focused block on the hardest available task, sized to a deep-work session. */
    public Map<String, Object> deepWorkBlock(String userId, EnergyLevel energy) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "DEEP_WORK_BLOCK");
        out.put("energy", energy.name());

        if (energy == EnergyLevel.EXHAUSTED) {
            out.put("hasBlock", false);
            out.put("message", "Ты вымотан, сэр — не время для глубокой работы. Лучше отдохнуть.");
            return out;
        }

        List<Task> ranked = ranker.rank(taskRepository.findActiveTasks(userId), energy, false);
        Task candidate = ranked.stream().filter(ranker::isHard).findFirst().orElse(null);

        if (candidate == null) {
            out.put("hasBlock", false);
            out.put("message", "Нет задач, подходящих для глубокого погружения, сэр.");
            return out;
        }

        int blockMinutes = Math.max(DEEP_WORK_MIN_MINUTES,
                candidate.getEstimatedDuration() == null ? DEEP_WORK_MIN_MINUTES : candidate.getEstimatedDuration());
        out.put("hasBlock", true);
        out.put("taskId", candidate.getId());
        out.put("title", candidate.getTitle());
        out.put("blockMinutes", blockMinutes);
        out.put("message", "Блок глубокой работы, сэр: «" + candidate.getTitle() + "» — " + blockMinutes
                + " мин без отвлечений.");
        return out;
    }

    private boolean isEssential(Task task) {
        if (task.getPriority() == TaskPriority.URGENT) {
            return true;
        }
        Instant due = task.getDueDate();
        return due != null && due.isBefore(Instant.now());
    }

    private Map<String, Object> summary(Task task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.getId());
        m.put("title", task.getTitle());
        m.put("priority", task.getPriority());
        m.put("estimatedDuration", task.getEstimatedDuration());
        return m;
    }
}
