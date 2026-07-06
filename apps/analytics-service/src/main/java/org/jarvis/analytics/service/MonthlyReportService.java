package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.dto.AnomalyDTO;
import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.dto.ExpenseSummaryDTO;
import org.jarvis.analytics.dto.OvertimeSummaryDTO;
import org.jarvis.analytics.dto.SleepSummaryDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monthly rollup built on top of the weekly {@link InsightService} aggregates,
 * plus a refinement of {@link InsightService#budgetForecast()} that excludes
 * anomalous high-spend days from the daily run-rate for a more realistic
 * month-end projection.
 */
@Service
@RequiredArgsConstructor
public class MonthlyReportService {

    private static final int MONTHLY_WINDOW_DAYS = 30;
    // Scaled from InsightService's weekly baseline (8h over a 7-day window) to a 30-day window.
    private static final int MONTHLY_BASELINE_HOURS = 35;
    private static final double ANOMALY_K = 2.0;
    private static final int MIN_ANOMALY_WINDOW = 3;

    private final AnalyticsService analyticsService;
    private final InsightService insightService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final ConsistencyService consistencyService;
    private final Clock clock;

    public Map<String, Object> monthlyReport() {
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);

        List<ExpenseSummaryDTO> byCategory = safeCategories(month, today);
        BigDecimal monthTotal = byCategory.stream()
                .map(ExpenseSummaryDTO::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        SleepSummaryDTO sleep = safeSleep();
        OvertimeSummaryDTO overtime = safeOvertime();
        DayScoreDTO consistency = consistencyService.studyWorkConsistency(MONTHLY_WINDOW_DAYS);
        Map<String, Object> forecast = insightService.budgetForecast();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month.toString());
        out.put("spentSoFar", monthTotal);
        out.put("topCategories", byCategory.stream().limit(3).toList());
        out.put("sleep", sleep);
        out.put("overtime", overtime);
        out.put("consistency", consistency);
        out.put("budgetForecast", forecast);
        out.put("report", buildNarrative(month, monthTotal, sleep, overtime, consistency));
        return out;
    }

    /** Refines the naive linear {@link InsightService#budgetForecast()} by excluding anomalous high-spend days. */
    public Map<String, Object> refinedOverspendForecast() {
        Map<String, Object> naive = insightService.budgetForecast();
        int dayOfMonth = ((Number) naive.get("dayOfMonth")).intValue();
        int daysInMonth = ((Number) naive.get("daysInMonth")).intValue();
        double spentSoFar = ((Number) naive.get("spentSoFar")).doubleValue();
        YearMonth currentMonth = YearMonth.from(LocalDate.now(clock));

        // Only count anomalies that fall within the current month: spentSoFar/dayOfMonth are
        // strictly current-month figures, but the trailing detection window (widened to at
        // least MIN_ANOMALY_WINDOW days) can reach back into the previous month near month start.
        List<AnomalyDTO> anomalies = anomalyDetectionService.detectExpenseAnomalies(
                        Math.max(MIN_ANOMALY_WINDOW, dayOfMonth), ANOMALY_K)
                .stream()
                .filter(a -> YearMonth.from(a.day()).equals(currentMonth))
                .toList();
        double excludedTotal = anomalies.stream().mapToDouble(AnomalyDTO::value).sum();
        double adjustedSpent = Math.max(0.0, spentSoFar - excludedTotal);
        double adjustedDailyRate = adjustedSpent / Math.max(1, dayOfMonth - anomalies.size());
        double adjustedProjection = adjustedDailyRate * daysInMonth;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("naive", naive);
        out.put("excludedAnomalies", anomalies);
        out.put("adjustedDailyRate", round(adjustedDailyRate));
        out.put("adjustedProjectedMonthEnd", round(adjustedProjection));
        out.put("explanation", anomalies.isEmpty()
                ? "Аномальных дней трат не обнаружено — уточнённый прогноз совпадает с базовым."
                : "Исключено " + anomalies.size() + " аномальных дн. трат (" + round(excludedTotal)
                        + ") из расчёта дневной ставки — так прогноз конца месяца точнее.");
        return out;
    }

    private List<ExpenseSummaryDTO> safeCategories(YearMonth month, LocalDate today) {
        List<ExpenseSummaryDTO> result = analyticsService.getExpensesByCategory(month.atDay(1), today);
        return result == null ? List.of() : result;
    }

    private SleepSummaryDTO safeSleep() {
        try {
            return analyticsService.getSleepSummary(MONTHLY_WINDOW_DAYS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private OvertimeSummaryDTO safeOvertime() {
        try {
            return analyticsService.getOvertimeSummary(MONTHLY_WINDOW_DAYS, MONTHLY_BASELINE_HOURS);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String buildNarrative(YearMonth month, BigDecimal monthTotal, SleepSummaryDTO sleep,
            OvertimeSummaryDTO overtime, DayScoreDTO consistency) {
        StringBuilder sb = new StringBuilder();
        sb.append("Отчёт за ").append(month).append(". Потрачено ").append(monthTotal).append(". ");
        if (sleep != null && sleep.getAverageHours() != null) {
            sb.append("Средний сон ").append(round(sleep.getAverageHours())).append(" ч/сутки. ");
        }
        if (overtime != null && overtime.getOvertimeHours() != null) {
            sb.append("Переработка +").append(overtime.getOvertimeHours()).append(" ч сверх нормы. ");
        }
        sb.append("Индекс стабильности работы/учёбы: ").append(consistency.score()).append("/100 (")
                .append(consistency.grade()).append(").");
        return sb.toString().trim();
    }

    private double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
