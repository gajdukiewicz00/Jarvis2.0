package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.InsightDTO;
import org.jarvis.analytics.dto.OvertimeSummaryDTO;
import org.jarvis.analytics.dto.SleepSummaryDTO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives human-readable insights, a day-score, and a daily report from the
 * raw life-tracker aggregates. Each insight carries an explanation (the "why").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightService {

    private static final double SLEEP_TARGET = 8.0;
    private static final double SLEEP_WARN = 7.0;
    private static final double SPEND_CONCENTRATION = 0.40;

    private final LifeTrackerClient lifeTrackerClient;
    private final AnalyticsService analyticsService;
    private final Clock clock;

    public List<InsightDTO> autoInsights() {
        List<InsightDTO> out = new ArrayList<>();

        SleepSummaryDTO sleep = safeSleep();
        if (sleep != null && sleep.getAverageHours() != null
                && sleep.getDaysSampled() != null && sleep.getDaysSampled() > 0) {
            double avg = sleep.getAverageHours();
            if (avg < SLEEP_WARN) {
                out.add(new InsightDTO("LOW_SLEEP", "Недосып",
                        "Средний сон за неделю " + round(avg) + " ч (<7). Стоит лечь раньше.", "WARN"));
            } else {
                out.add(new InsightDTO("SLEEP_OK", "Сон в норме",
                        "Средний сон " + round(avg) + " ч за " + sleep.getDaysSampled() + " дн.", "INFO"));
            }
        }

        OvertimeSummaryDTO ot = safeOvertime();
        if (ot != null && ot.getOvertimeHours() != null && ot.getOvertimeHours() > 0) {
            out.add(new InsightDTO("OVERTIME", "Переработки",
                    "За неделю +" + ot.getOvertimeHours() + " ч сверх нормы. Запланируйте отдых.", "WARN"));
        }

        List<ExpenseDTO> expenses = safe(lifeTrackerClient.getExpenses());
        if (!expenses.isEmpty()) {
            Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            for (ExpenseDTO e : expenses) {
                if (e.getAmount() == null) {
                    continue;
                }
                total = total.add(e.getAmount());
                String cat = e.getCategory() == null || e.getCategory().isBlank() ? "прочее" : e.getCategory();
                byCategory.merge(cat, e.getAmount(), BigDecimal::add);
            }
            if (total.signum() > 0) {
                Map.Entry<String, BigDecimal> top = byCategory.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElse(null);
                if (top != null) {
                    double share = top.getValue().doubleValue() / total.doubleValue();
                    if (share > SPEND_CONCENTRATION) {
                        out.add(new InsightDTO("SPEND_CONCENTRATION", "Перекос трат",
                                "Категория «" + top.getKey() + "» — " + pct(share) + "% всех расходов.", "INFO"));
                    }
                }
            }
        }

        if (sleep != null && sleep.getAverageHours() != null && sleep.getAverageHours() < SLEEP_WARN
                && ot != null && ot.getOvertimeHours() != null && ot.getOvertimeHours() > 0) {
            out.add(new InsightDTO("SLEEP_WORK_CORR", "Недосып ↔ переработки",
                    "Мало сна и переработки идут вместе — продуктивность под угрозой.", "WARN"));
        }

        if (out.isEmpty()) {
            out.add(new InsightDTO("ALL_GOOD", "Всё спокойно", "Заметных аномалий не обнаружено.", "INFO"));
        }
        return out;
    }

    /** Projects month-end spend from the current daily rate. */
    public Map<String, Object> budgetForecast() {
        List<ExpenseDTO> expenses = safe(lifeTrackerClient.getExpenses());
        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);
        BigDecimal spent = BigDecimal.ZERO;
        for (ExpenseDTO e : expenses) {
            if (e.getAmount() == null || e.getOccurredAt() == null) {
                continue;
            }
            if (YearMonth.from(e.getOccurredAt()).equals(month)) {
                spent = spent.add(e.getAmount());
            }
        }
        int dayOfMonth = today.getDayOfMonth();
        int daysInMonth = month.lengthOfMonth();
        double dailyRate = spent.doubleValue() / Math.max(1, dayOfMonth);
        double projected = dailyRate * daysInMonth;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month.toString());
        out.put("spentSoFar", round2(spent.doubleValue()));
        out.put("dayOfMonth", dayOfMonth);
        out.put("daysInMonth", daysInMonth);
        out.put("dailyRate", round2(dailyRate));
        out.put("projectedMonthEnd", round2(projected));
        return out;
    }

    private double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }

    public DayScoreDTO dayScore() {
        SleepSummaryDTO sleep = safeSleep();
        OvertimeSummaryDTO ot = safeOvertime();
        double sleepAvg = sleep != null && sleep.getAverageHours() != null ? sleep.getAverageHours() : 0.0;
        double work = ot != null && ot.getTrackedWorkHours() != null ? ot.getTrackedWorkHours() : 0.0;
        int overtime = ot != null && ot.getOvertimeHours() != null ? ot.getOvertimeHours() : 0;

        double sleepPts = Math.max(0, Math.min(50, sleepAvg / SLEEP_TARGET * 50));
        double workPts = work > 0 ? 30 : 10;
        double penalty = Math.min(20, overtime * 4.0);
        int score = (int) Math.round(Math.max(0, Math.min(100, sleepPts + workPts + 20 - penalty)));
        String grade = score >= 80 ? "A" : score >= 60 ? "B" : score >= 40 ? "C" : "D";

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("sleepAvgHours", round(sleepAvg));
        components.put("trackedWorkHours", round(work));
        components.put("overtimeHours", overtime);
        return new DayScoreDTO(score, grade, components);
    }

    public Map<String, Object> dailyReport() {
        List<InsightDTO> insights = autoInsights();
        DayScoreDTO score = dayScore();
        StringBuilder sb = new StringBuilder();
        sb.append("Сводка дня. Оценка ").append(score.score()).append("/100 (").append(score.grade()).append("). ");
        for (InsightDTO i : insights) {
            sb.append(i.title()).append(": ").append(i.detail()).append(' ');
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("insights", insights);
        out.put("report", sb.toString().trim());
        return out;
    }

    /** Weekly rollup built from the same trailing-7-day aggregates used by {@link #dailyReport()}. */
    public Map<String, Object> weeklyDigest() {
        SleepSummaryDTO sleep = safeSleep();
        OvertimeSummaryDTO overtime = safeOvertime();
        List<InsightDTO> insights = autoInsights();

        StringBuilder sb = new StringBuilder();
        sb.append("Сводка недели. ");
        if (sleep != null && sleep.getAverageHours() != null) {
            sb.append("Средний сон ").append(round(sleep.getAverageHours())).append(" ч/сутки за ")
                    .append(sleep.getDaysSampled()).append(" дн. ");
        }
        if (overtime != null && overtime.getOvertimeHours() != null) {
            sb.append("Переработка +").append(overtime.getOvertimeHours()).append(" ч сверх нормы за неделю. ");
        }
        for (InsightDTO i : insights) {
            sb.append(i.title()).append(": ").append(i.detail()).append(' ');
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sleep", sleep);
        out.put("overtime", overtime);
        out.put("insights", insights);
        out.put("digest", sb.toString().trim());
        return out;
    }

    private SleepSummaryDTO safeSleep() {
        try {
            return analyticsService.getSleepSummary(7);
        } catch (RuntimeException e) {
            log.debug("sleep summary unavailable: {}", e.getMessage());
            return null;
        }
    }

    private OvertimeSummaryDTO safeOvertime() {
        try {
            return analyticsService.getOvertimeSummary(7, 8);
        } catch (RuntimeException e) {
            log.debug("overtime summary unavailable: {}", e.getMessage());
            return null;
        }
    }

    private List<ExpenseDTO> safe(List<ExpenseDTO> list) {
        return list == null ? List.of() : list;
    }

    private double round(double d) {
        return Math.round(d * 10.0) / 10.0;
    }

    private int pct(double d) {
        return (int) Math.round(d * 100);
    }
}
