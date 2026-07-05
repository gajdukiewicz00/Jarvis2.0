package org.jarvis.lifetracker.dto;

import java.time.LocalDate;

/**
 * Current/longest streak summary for a single named habit
 * (backed by {@code WellnessType.HABIT} logs where {@code textValue} is the habit name).
 */
public record HabitStreakDTO(
        String habitName,
        int currentStreak,
        int longestStreak,
        LocalDate lastLoggedDay,
        boolean completedToday,
        int totalCheckIns
) {
}
