package org.jarvis.lifetracker.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.jarvis.lifetracker.dto.HabitStreakDTO;
import org.jarvis.lifetracker.dto.WellnessSummaryDTO;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Habit streak calculation + numeric metric summaries (weight / mood / steps / sleep / workout)
 * built on top of {@link WellnessLog}.
 */
@Service
@RequiredArgsConstructor
public class WellnessService {

    private final WellnessLogRepository repository;

    /** Streak for a single habit, matched by {@code textValue} (case-insensitive). */
    public HabitStreakDTO habitStreak(String userId, String habitName) {
        List<WellnessLog> logs = repository.findByUserIdAndTypeOrderByLoggedAtAsc(userId, WellnessType.HABIT).stream()
                .filter(log -> habitName.equalsIgnoreCase(log.getTextValue()))
                .toList();
        return buildStreak(habitName, logs);
    }

    /** Streaks for every distinct habit name the user has ever logged. */
    public List<HabitStreakDTO> listHabitStreaks(String userId) {
        List<WellnessLog> all = repository.findByUserIdAndTypeOrderByLoggedAtAsc(userId, WellnessType.HABIT);
        Set<String> names = new LinkedHashSet<>();
        for (WellnessLog log : all) {
            if (log.getTextValue() != null && !log.getTextValue().isBlank()) {
                names.add(log.getTextValue());
            }
        }
        List<HabitStreakDTO> streaks = new ArrayList<>();
        for (String name : names) {
            List<WellnessLog> forHabit = all.stream()
                    .filter(log -> name.equalsIgnoreCase(log.getTextValue()))
                    .toList();
            streaks.add(buildStreak(name, forHabit));
        }
        return streaks;
    }

    private HabitStreakDTO buildStreak(String habitName, List<WellnessLog> logs) {
        if (logs.isEmpty()) {
            return new HabitStreakDTO(habitName, 0, 0, null, false, 0);
        }

        // Collapse to one "done" flag per calendar day: any positive check-in that day counts as done.
        Map<LocalDate, Boolean> doneByDay = new TreeMap<>();
        for (WellnessLog log : logs) {
            boolean done = log.getNumericValue() != null && log.getNumericValue() > 0;
            doneByDay.merge(log.getDay(), done, (existing, incoming) -> existing || incoming);
        }
        List<LocalDate> sortedDays = new ArrayList<>(doneByDay.keySet());

        int longestStreak = 0;
        int running = 0;
        LocalDate previousDay = null;
        for (LocalDate day : sortedDays) {
            boolean done = doneByDay.get(day);
            if (!done) {
                running = 0;
            } else if (previousDay != null && day.equals(previousDay.plusDays(1))) {
                running++;
            } else {
                running = 1;
            }
            longestStreak = Math.max(longestStreak, running);
            previousDay = day;
        }

        LocalDate today = LocalDate.now();
        LocalDate lastLoggedDay = sortedDays.get(sortedDays.size() - 1);
        boolean completedToday = Boolean.TRUE.equals(doneByDay.get(today));

        LocalDate lastDoneDay = null;
        for (int i = sortedDays.size() - 1; i >= 0; i--) {
            if (doneByDay.get(sortedDays.get(i))) {
                lastDoneDay = sortedDays.get(i);
                break;
            }
        }

        int currentStreak = 0;
        // The streak is only "alive" if the most recent completion was today or yesterday
        // (a grace day so a habit logged this morning doesn't look broken before midnight).
        if (lastDoneDay != null && !lastDoneDay.isBefore(today.minusDays(1))) {
            LocalDate cursor = lastDoneDay;
            while (Boolean.TRUE.equals(doneByDay.get(cursor))) {
                currentStreak++;
                cursor = cursor.minusDays(1);
            }
        }

        return new HabitStreakDTO(habitName, currentStreak, longestStreak, lastLoggedDay, completedToday, logs.size());
    }

    /** Average / min / max / latest for a numeric wellness type within an inclusive date range. */
    public WellnessSummaryDTO summary(String userId, WellnessType type, LocalDate from, LocalDate to) {
        List<WellnessLog> logs = repository.findByUserIdAndTypeOrderByLoggedAtAsc(userId, type).stream()
                .filter(log -> !log.getDay().isBefore(from) && !log.getDay().isAfter(to))
                .filter(log -> log.getNumericValue() != null)
                .toList();
        if (logs.isEmpty()) {
            return new WellnessSummaryDTO(type, from, to, 0, null, null, null, null, null);
        }

        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (WellnessLog log : logs) {
            double value = log.getNumericValue();
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        WellnessLog latestLog = logs.get(logs.size() - 1);
        double average = sum / logs.size();

        return new WellnessSummaryDTO(type, from, to, logs.size(), average, min, max,
                latestLog.getNumericValue(), latestLog.getDay());
    }
}
