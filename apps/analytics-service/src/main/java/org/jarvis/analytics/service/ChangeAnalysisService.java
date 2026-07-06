package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.util.StatsMath;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based "what changed?" / "why did my week go bad?" explainer. Compares
 * the trailing 7-day window against the 7 days before it across sleep, work,
 * and spending, and explains each delta in plain language.
 */
@Service
@RequiredArgsConstructor
public class ChangeAnalysisService {

    private static final int WEEK_DAYS = 7;
    private static final double FLAT_DELTA_THRESHOLD = 0.05;

    private final TrendSeriesService trendSeriesService;
    private final Clock clock;

    public Map<String, Object> whatChanged() {
        LocalDate today = LocalDate.now(clock);
        LocalDate currentStart = today.minusDays(WEEK_DAYS - 1L);
        LocalDate previousStart = currentStart.minusDays(WEEK_DAYS);
        LocalDate previousEnd = currentStart.minusDays(1);

        Map<LocalDate, Double> sleep = trendSeriesService.dailySleepHours(WEEK_DAYS * 2);
        Map<LocalDate, Double> work = trendSeriesService.dailyWorkHours(WEEK_DAYS * 2);
        Map<LocalDate, Double> spend = trendSeriesService.dailyExpenseTotals(WEEK_DAYS * 2);

        List<Map<String, Object>> changes = new ArrayList<>();
        changes.add(sleepChange(average(sleep, previousStart, previousEnd), average(sleep, currentStart, today)));
        changes.add(workChange(sum(work, previousStart, previousEnd), sum(work, currentStart, today)));
        changes.add(spendChange(sum(spend, previousStart, previousEnd), sum(spend, currentStart, today)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentWeek", range(currentStart, today));
        out.put("previousWeek", range(previousStart, previousEnd));
        out.put("changes", changes);
        out.put("summary", buildSummary(changes));
        return out;
    }

    /** Narrows {@link #whatChanged()} to regressions only, for a "why did my week go bad?" answer. */
    public Map<String, Object> whyWeekWentBad() {
        Map<String, Object> full = whatChanged();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) full.get("changes");
        List<Map<String, Object>> regressions = changes.stream()
                .filter(c -> "WORSE".equals(c.get("verdict")))
                .toList();

        String narrative = regressions.isEmpty()
                ? "Ничего заметно не ухудшилось по сравнению с прошлой неделей."
                : "Похоже, неделя пошла хуже из-за: " + regressions.stream()
                        .map(c -> String.valueOf(c.get("explanation")))
                        .reduce((a, b) -> a + " " + b)
                        .orElse("");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentWeek", full.get("currentWeek"));
        out.put("previousWeek", full.get("previousWeek"));
        out.put("regressions", regressions);
        out.put("narrative", narrative);
        return out;
    }

    private Map<String, Object> sleepChange(double previous, double current) {
        double delta = round(current - previous);
        String verdict = Math.abs(delta) < FLAT_DELTA_THRESHOLD ? "FLAT" : delta < 0 ? "WORSE" : "BETTER";
        String explanation = "Средний сон изменился с " + round(previous) + " до " + round(current)
                + " ч/сутки (" + signed(delta) + ").";
        return entry("sleepAvgHours", previous, current, delta, verdict, explanation);
    }

    private Map<String, Object> workChange(double previous, double current) {
        double delta = round(current - previous);
        String verdict = Math.abs(delta) < FLAT_DELTA_THRESHOLD ? "FLAT" : delta > 0 ? "WORSE" : "BETTER";
        String explanation = "Рабочие часы за неделю изменились с " + round(previous) + " до " + round(current)
                + " ч (" + signed(delta) + ").";
        return entry("workHoursTotal", previous, current, delta, verdict, explanation);
    }

    private Map<String, Object> spendChange(double previous, double current) {
        double delta = round(current - previous);
        String verdict = Math.abs(delta) < FLAT_DELTA_THRESHOLD ? "FLAT" : delta > 0 ? "WORSE" : "BETTER";
        String explanation = "Траты за неделю изменились с " + round(previous) + " до " + round(current)
                + " (" + signed(delta) + ").";
        return entry("spendTotal", previous, current, delta, verdict, explanation);
    }

    private Map<String, Object> entry(String metric, double previous, double current, double delta,
            String verdict, String explanation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        m.put("previous", round(previous));
        m.put("current", round(current));
        m.put("delta", delta);
        m.put("verdict", verdict);
        m.put("explanation", explanation);
        return m;
    }

    private Map<String, String> range(LocalDate from, LocalDate to) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("from", from.toString());
        m.put("to", to.toString());
        return m;
    }

    private String buildSummary(List<Map<String, Object>> changes) {
        StringBuilder sb = new StringBuilder("Сравнение с прошлой неделей. ");
        for (Map<String, Object> c : changes) {
            sb.append(c.get("explanation")).append(' ');
        }
        return sb.toString().trim();
    }

    private String signed(double delta) {
        return (delta >= 0 ? "+" : "") + delta;
    }

    private double average(Map<LocalDate, Double> series, LocalDate from, LocalDate to) {
        List<Double> values = valuesInRange(series, from, to);
        return values.isEmpty() ? 0.0 : StatsMath.mean(values);
    }

    private double sum(Map<LocalDate, Double> series, LocalDate from, LocalDate to) {
        return valuesInRange(series, from, to).stream().mapToDouble(Double::doubleValue).sum();
    }

    private List<Double> valuesInRange(Map<LocalDate, Double> series, LocalDate from, LocalDate to) {
        List<Double> values = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> e : series.entrySet()) {
            if (!e.getKey().isBefore(from) && !e.getKey().isAfter(to)) {
                values.add(e.getValue());
            }
        }
        return values;
    }

    private double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
