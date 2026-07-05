package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.HabitStreakDTO;
import org.jarvis.analytics.dto.WellnessLogDTO;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Habit-streak analytics from life-tracker's WellnessLog HABIT entries
 * ({@code numericValue}=1 done / 0 skipped, {@code textValue}=habit name).
 * A day with no logged entry counts as not-done for streak purposes.
 */
@Service
@RequiredArgsConstructor
public class HabitStreakService {

    private final LifeTrackerClient lifeTrackerClient;
    private final Clock clock;

    public List<HabitStreakDTO> habitStreaks(int windowDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusDays(Math.max(1, windowDays) - 1L);

        Map<String, Map<LocalDate, Boolean>> byHabit = new LinkedHashMap<>();
        for (WellnessLogDTO log : safeList(lifeTrackerClient.getWellnessTrend("HABIT"))) {
            if (log == null || log.getDay() == null) {
                continue;
            }
            if (log.getDay().isBefore(cutoff) || log.getDay().isAfter(today)) {
                continue;
            }
            String habit = log.getTextValue() == null || log.getTextValue().isBlank()
                    ? "habit" : log.getTextValue().trim();
            boolean done = log.getNumericValue() != null && log.getNumericValue() > 0;
            byHabit.computeIfAbsent(habit, k -> new LinkedHashMap<>()).put(log.getDay(), done);
        }

        List<HabitStreakDTO> out = new ArrayList<>();
        for (Map.Entry<String, Map<LocalDate, Boolean>> entry : byHabit.entrySet()) {
            out.add(buildStreak(entry.getKey(), entry.getValue(), cutoff, today, windowDays));
        }
        out.sort(Comparator.comparing(HabitStreakDTO::habit));
        return out;
    }

    private HabitStreakDTO buildStreak(String habit, Map<LocalDate, Boolean> daysDone, LocalDate cutoff,
            LocalDate today, int windowDays) {
        int activeDays = (int) daysDone.values().stream().filter(Boolean::booleanValue).count();

        int currentStreak = 0;
        LocalDate cursor = today;
        while (!cursor.isBefore(cutoff) && Boolean.TRUE.equals(daysDone.get(cursor))) {
            currentStreak++;
            cursor = cursor.minusDays(1);
        }

        int longestStreak = 0;
        int running = 0;
        for (LocalDate day = cutoff; !day.isAfter(today); day = day.plusDays(1)) {
            if (Boolean.TRUE.equals(daysDone.get(day))) {
                running++;
                longestStreak = Math.max(longestStreak, running);
            } else {
                running = 0;
            }
        }

        double consistencyPct = windowDays > 0 ? round(activeDays * 100.0 / windowDays) : 0.0;
        String explanation = currentStreak > 0
                ? "Текущая серия «" + habit + "»: " + currentStreak + " дн. подряд (лучшая — " + longestStreak
                        + "), выполнено " + activeDays + "/" + windowDays + " дн."
                : "Серия «" + habit + "» прервана. Лучшая серия за окно — " + longestStreak
                        + " дн., выполнено " + activeDays + "/" + windowDays + " дн.";

        return new HabitStreakDTO(habit, currentStreak, longestStreak, activeDays, windowDays, consistencyPct,
                explanation);
    }

    private double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    private List<WellnessLogDTO> safeList(List<WellnessLogDTO> values) {
        return values == null ? List.of() : values;
    }
}
