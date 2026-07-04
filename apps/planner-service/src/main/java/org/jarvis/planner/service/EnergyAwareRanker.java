package org.jarvis.planner.service;

import org.jarvis.planner.model.EnergyLevel;
import org.jarvis.planner.model.Task;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * B3 — orders tasks by the user's current energy. High energy favours hard /
 * deep-work tasks; low energy favours light ones; exhausted strongly avoids
 * heavy work (unless explicitly forced). NORMAL keeps priority-driven ordering.
 */
@Service
public class EnergyAwareRanker {

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
        int base = priority * 10;
        if (force || energy == null || energy == EnergyLevel.NORMAL) {
            return base; // priority-driven, no energy adjustment
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
