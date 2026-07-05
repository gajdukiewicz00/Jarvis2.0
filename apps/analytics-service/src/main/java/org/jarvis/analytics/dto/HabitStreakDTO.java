package org.jarvis.analytics.dto;

/**
 * Streak analytics for a single named habit (life-tracker WellnessLog HABIT
 * entries) over a trailing window.
 */
public record HabitStreakDTO(
        String habit,
        int currentStreakDays,
        int longestStreakDays,
        int activeDays,
        int windowDays,
        double consistencyPct,
        String explanation) {
}
