package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.TimeRecordDTO;
import org.jarvis.analytics.dto.WellnessLogDTO;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds day-keyed metric series (sleep hours, work hours, expense totals) from
 * life-tracker's raw records, feeding the insight engine's correlation,
 * consistency, change-analysis, and anomaly-detection features.
 *
 * <p>Intentionally independent from {@link AnalyticsService}'s own per-day sleep
 * merge (same source precedence, kept separate so each class stays safely
 * testable and changeable in isolation).</p>
 */
@Service
@RequiredArgsConstructor
public class TrendSeriesService {

    private static final Set<String> SLEEP_KEYWORDS = Set.of("sleep", "slept", "nap", "rest");
    private static final Set<String> WORK_KEYWORDS = Set.of("work", "focus", "coding", "meeting", "study");

    private final LifeTrackerClient lifeTrackerClient;
    private final Clock clock;

    /**
     * Day -> sleep hours for the trailing window. WellnessLog SLEEP entries
     * (health-entry / phone sync) override the legacy time-record estimate per
     * day, mirroring {@link AnalyticsService#getSleepSummary(int)}'s precedence.
     */
    public Map<LocalDate, Double> dailySleepHours(int trailingDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusDays(Math.max(1, trailingDays) - 1L);

        Map<LocalDate, Double> byDay = new HashMap<>();
        safeTimeRecords().stream()
                .filter(this::hasDuration)
                .filter(r -> matchesKeyword(r, SLEEP_KEYWORDS))
                .filter(r -> withinWindow(r.getStartTime().toLocalDate(), cutoff, today))
                .forEach(r -> byDay.merge(r.getStartTime().toLocalDate(), r.getDurationSeconds() / 3600.0, Double::sum));

        for (WellnessLogDTO w : safeWellness("SLEEP")) {
            if (w == null || w.getNumericValue() == null || w.getDay() == null) {
                continue;
            }
            if (w.getDay().isBefore(cutoff) || w.getDay().isAfter(today)) {
                continue;
            }
            byDay.put(w.getDay(), w.getNumericValue());
        }
        return byDay;
    }

    /** Day -> tracked work/study hours for the trailing window. */
    public Map<LocalDate, Double> dailyWorkHours(int trailingDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusDays(Math.max(1, trailingDays) - 1L);

        Map<LocalDate, Double> byDay = new HashMap<>();
        safeTimeRecords().stream()
                .filter(this::hasDuration)
                .filter(r -> matchesKeyword(r, WORK_KEYWORDS))
                .filter(r -> withinWindow(r.getStartTime().toLocalDate(), cutoff, today))
                .forEach(r -> byDay.merge(r.getStartTime().toLocalDate(), r.getDurationSeconds() / 3600.0, Double::sum));
        return byDay;
    }

    /** Day -> total EXPENSE-type spend for the trailing window. */
    public Map<LocalDate, Double> dailyExpenseTotals(int trailingDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusDays(Math.max(1, trailingDays) - 1L);

        Map<LocalDate, Double> byDay = new HashMap<>();
        for (ExpenseDTO e : safeExpenses()) {
            if (e == null || e.getAmount() == null || e.getOccurredAt() == null) {
                continue;
            }
            if (e.getType() != null && !"EXPENSE".equalsIgnoreCase(e.getType())) {
                continue;
            }
            LocalDate day = e.getOccurredAt().toLocalDate();
            if (day.isBefore(cutoff) || day.isAfter(today)) {
                continue;
            }
            byDay.merge(day, e.getAmount().doubleValue(), Double::sum);
        }
        return byDay;
    }

    private boolean hasDuration(TimeRecordDTO record) {
        return record != null && record.getDurationSeconds() != null && record.getDurationSeconds() > 0
                && record.getStartTime() != null;
    }

    private boolean withinWindow(LocalDate day, LocalDate cutoff, LocalDate today) {
        return !day.isBefore(cutoff) && !day.isAfter(today);
    }

    private boolean matchesKeyword(TimeRecordDTO record, Set<String> keywords) {
        return matches(record.getCategory(), keywords) || matches(record.getActivity(), keywords);
    }

    private boolean matches(String value, Set<String> keywords) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(normalized::contains);
    }

    private List<TimeRecordDTO> safeTimeRecords() {
        List<TimeRecordDTO> values = lifeTrackerClient.getTimeRecords();
        return values == null ? List.of() : values;
    }

    private List<WellnessLogDTO> safeWellness(String type) {
        List<WellnessLogDTO> values = lifeTrackerClient.getWellnessTrend(type);
        return values == null ? List.of() : values;
    }

    private List<ExpenseDTO> safeExpenses() {
        List<ExpenseDTO> values = lifeTrackerClient.getExpenses();
        return values == null ? List.of() : values;
    }
}
