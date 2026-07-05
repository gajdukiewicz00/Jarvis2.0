package org.jarvis.planner.service;

import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.PlanMode;
import org.jarvis.planner.model.Task;
import org.jarvis.planner.model.TaskCategory;
import org.jarvis.planner.model.TaskPriority;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * B3 — orders tasks by the user's current energy. High energy favours hard /
 * deep-work tasks; low energy favours light ones; exhausted strongly avoids
 * heavy work (unless explicitly forced). NORMAL keeps priority-driven ordering.
 * Deadline pressure (P1 #10) is folded into every energy state: a task nearer
 * its deadline always ranks above an equal-priority task that is not.
 */
@Service
public class EnergyAwareRanker {

    /** Deadline-pressure buckets, closest deadline first. */
    private enum DeadlineBucket {
        NONE, LATER, DUE_THIS_WEEK, DUE_SOON, DUE_TODAY, OVERDUE
    }

    public List<Task> rank(List<Task> tasks, EnergyLevel energy, boolean force) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        return tasks.stream()
                .sorted(Comparator.comparingInt((Task t) -> score(t, energy, force)).reversed())
                .toList();
    }

    /** Higher score = recommended sooner. */
    public int score(Task task, EnergyLevel energy, boolean force) {
        int priority = task.getPriority() == null ? 1 : task.getPriority().ordinal();
        int base = priority * 10 + deadlinePressure(task);
        if (force || energy == null || energy == EnergyLevel.NORMAL) {
            return base; // priority + deadline driven, no energy adjustment
        }
        boolean hard = isHard(task);
        boolean light = isLight(task);
        int adjustment = switch (energy) {
            case HIGH -> hard ? 100 : (light ? -20 : 0);
            case LOW -> light ? 80 : (hard ? -40 : 0);
            case EXHAUSTED -> light ? 120 : (hard ? -300 : -60);
            case NORMAL -> 0;
        };
        return base + adjustment;
    }

    /**
     * Energy-aware ranking additionally steered by a persisted
     * {@link PlanMode} selection (P1 — plan-mode-aware ranking). {@code mode}
     * adjusts the score on top of the energy adjustment; {@code force} still
     * suppresses both.
     */
    public List<Task> rank(List<Task> tasks, EnergyLevel energy, boolean force, PlanMode mode) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        return tasks.stream()
                .sorted(Comparator.comparingInt((Task t) -> score(t, energy, force, mode)).reversed())
                .toList();
    }

    /** Higher score = recommended sooner, also accounting for the selected {@link PlanMode}. */
    public int score(Task task, EnergyLevel energy, boolean force, PlanMode mode) {
        int base = score(task, energy, force);
        if (force || mode == null) {
            return base;
        }
        return base + planModeAdjustment(task, mode);
    }

    private int planModeAdjustment(Task task, PlanMode mode) {
        boolean hard = isHard(task);
        boolean light = isLight(task);
        return switch (mode) {
            case DEEP_WORK -> hard ? 100 : (light ? -20 : 0);
            case RECOVERY -> light ? 100 : (hard ? -250 : -40);
            case STUDY -> task.getCategory() == TaskCategory.STUDY ? 80 : 0;
            case MINIMUM_VIABLE_DAY -> isEssential(task) ? 150 : -60;
            case NORMAL -> 0;
        };
    }

    /** Urgent or already-overdue — mirrors {@code PlanModeService#isEssential}. */
    private boolean isEssential(Task task) {
        if (task.getPriority() == TaskPriority.URGENT) {
            return true;
        }
        Instant due = task.getDueDate();
        return due != null && due.isBefore(Instant.now());
    }

    /**
     * Deadline-pressure score contribution — nearer deadlines score higher so
     * that, all else equal, a task closer to its deadline outranks one that
     * isn't (P1 #10: deadline-pressure scoring).
     */
    public int deadlinePressure(Task task) {
        return switch (deadlineBucket(task)) {
            case OVERDUE -> 90;
            case DUE_TODAY -> 70;
            case DUE_SOON -> 45;
            case DUE_THIS_WEEK -> 20;
            case LATER -> 5;
            case NONE -> 0;
        };
    }

    /** Human/machine-readable deadline urgency label for API responses. */
    public String deadlineLabel(Task task) {
        return deadlineBucket(task).name();
    }

    private DeadlineBucket deadlineBucket(Task task) {
        Instant due = task.getDueDate();
        if (due == null) {
            return DeadlineBucket.NONE;
        }
        Instant now = Instant.now();
        if (due.isBefore(now)) {
            return DeadlineBucket.OVERDUE;
        }
        long hoursUntilDue = Duration.between(now, due).toHours();
        if (hoursUntilDue <= 24) {
            return DeadlineBucket.DUE_TODAY;
        }
        if (hoursUntilDue <= 72) {
            return DeadlineBucket.DUE_SOON;
        }
        if (hoursUntilDue <= 24 * 7) {
            return DeadlineBucket.DUE_THIS_WEEK;
        }
        return DeadlineBucket.LATER;
    }

    public boolean isHard(Task task) {
        Integer d = task.getEstimatedDuration();
        return (d != null && d >= 90) || hasTag(task, "deep-work", "deep_work", "hard", "сложн", "глубок");
    }

    public boolean isLight(Task task) {
        Integer d = task.getEstimatedDuration();
        return (d != null && d <= 30) || hasTag(task, "light", "admin", "quick", "лёгк", "легк", "быстр");
    }

    /** Human explanation for why a task is recommended now. */
    public String explain(Task task, EnergyLevel energy) {
        String title = task == null ? "—" : task.getTitle();
        return switch (energy == null ? EnergyLevel.NORMAL : energy) {
            case HIGH -> "Высокая энергия, сэр — самое время для серьёзной задачи: «" + title + "».";
            case NORMAL -> "Сбалансированный день, сэр. По приоритету сейчас: «" + title + "».";
            case LOW -> "Энергии немного, сэр — начнём с лёгкого: «" + title + "».";
            case EXHAUSTED -> "Ты вымотан, сэр. Минимум на сегодня — «" + title
                    + "»; тяжёлое лучше отложить и отдохнуть.";
        };
    }

    private boolean hasTag(Task task, String... needles) {
        if (task.getTags() == null) {
            return false;
        }
        for (String tag : task.getTags()) {
            if (tag == null) {
                continue;
            }
            String t = tag.toLowerCase(Locale.ROOT);
            for (String n : needles) {
                if (t.contains(n)) {
                    return true;
                }
            }
        }
        return false;
    }
}
