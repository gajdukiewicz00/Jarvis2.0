package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.dto.AnomalyDTO;
import org.jarvis.analytics.util.StatsMath;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Flags days whose value exceeds {@code mean + k*stdDev} for a tracked daily
 * metric (expenses, sleep hours, work hours), with a plain-language why.
 */
@Service
@RequiredArgsConstructor
public class AnomalyDetectionService {

    private static final int MIN_SAMPLE_SIZE = 3;

    private final TrendSeriesService trendSeriesService;

    public List<AnomalyDTO> detectExpenseAnomalies(int windowDays, double k) {
        return detect("dailyExpenseTotal", trendSeriesService.dailyExpenseTotals(windowDays), k,
                (day, value, mean, stdDev) -> "Траты " + day + " составили " + round(value)
                        + " — выше среднего " + round(mean) + " на " + round(k) + "×σ (σ=" + round(stdDev)
                        + ") за " + windowDays + " дн.");
    }

    public List<AnomalyDTO> detectSleepAnomalies(int windowDays, double k) {
        return detect("dailySleepHours", trendSeriesService.dailySleepHours(windowDays), k,
                (day, value, mean, stdDev) -> "Сон " + day + " (" + round(value)
                        + " ч) значительно выше среднего " + round(mean) + " ч (σ=" + round(stdDev)
                        + ") за " + windowDays + " дн.");
    }

    public List<AnomalyDTO> detectWorkAnomalies(int windowDays, double k) {
        return detect("dailyWorkHours", trendSeriesService.dailyWorkHours(windowDays), k,
                (day, value, mean, stdDev) -> "Рабочих часов " + day + " (" + round(value)
                        + " ч) значительно выше среднего " + round(mean) + " ч (σ=" + round(stdDev)
                        + ") за " + windowDays + " дн.");
    }

    private List<AnomalyDTO> detect(String metric, Map<LocalDate, Double> series, double k, Explainer explainer) {
        if (series.size() < MIN_SAMPLE_SIZE) {
            return List.of();
        }
        List<Double> values = new ArrayList<>(series.values());
        double mean = StatsMath.mean(values);
        double stdDev = StatsMath.stdDev(values);
        if (stdDev <= 0.0) {
            return List.of();
        }
        double threshold = mean + k * stdDev;

        List<AnomalyDTO> anomalies = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> entry : series.entrySet()) {
            if (entry.getValue() > threshold) {
                anomalies.add(new AnomalyDTO(metric, entry.getKey(), round(entry.getValue()), round(mean),
                        round(stdDev), explainer.explain(entry.getKey(), entry.getValue(), mean, stdDev)));
            }
        }
        anomalies.sort(Comparator.comparing(AnomalyDTO::day));
        return anomalies;
    }

    @FunctionalInterface
    private interface Explainer {
        String explain(LocalDate day, double value, double mean, double stdDev);
    }

    private double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
