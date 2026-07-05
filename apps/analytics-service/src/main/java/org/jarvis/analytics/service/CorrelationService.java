package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.dto.CorrelationResultDTO;
import org.jarvis.analytics.util.StatsMath;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Correlates daily sleep hours against daily tracked work hours over a
 * trailing window, using a Pearson coefficient computed only across the days
 * both series have a value for.
 */
@Service
@RequiredArgsConstructor
public class CorrelationService {

    private final TrendSeriesService trendSeriesService;

    public CorrelationResultDTO sleepProductivityCorrelation(int windowDays) {
        Map<LocalDate, Double> sleep = trendSeriesService.dailySleepHours(windowDays);
        Map<LocalDate, Double> work = trendSeriesService.dailyWorkHours(windowDays);

        List<Double> xs = new ArrayList<>();
        List<Double> ys = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> entry : sleep.entrySet()) {
            Double workHours = work.get(entry.getKey());
            if (workHours != null) {
                xs.add(entry.getValue());
                ys.add(workHours);
            }
        }

        Optional<Double> coefficient = StatsMath.pearson(xs, ys);
        if (coefficient.isEmpty()) {
            return new CorrelationResultDTO("sleepHours", "workHours", null, xs.size(), "none", "none",
                    "Недостаточно пересекающихся дней (" + xs.size() + ") для расчёта корреляции за "
                            + windowDays + " дн.");
        }

        double r = round(coefficient.get());
        String strength = StatsMath.strengthLabel(r);
        String direction = r > 0 ? "positive" : r < 0 ? "negative" : "none";
        return new CorrelationResultDTO("sleepHours", "workHours", r, xs.size(), strength, direction,
                buildExplanation(r, strength, direction, xs.size(), windowDays));
    }

    private String buildExplanation(double r, String strength, String direction, int sampleSize, int windowDays) {
        if ("none".equals(strength)) {
            return "Связь между сном и рабочими часами за " + windowDays + " дн. не прослеживается (r=" + r
                    + ", n=" + sampleSize + ").";
        }
        String directionText = "positive".equals(direction)
                ? "больше сна совпадает с большим числом рабочих часов"
                : "больше сна совпадает с меньшим числом рабочих часов";
        String strengthRu = "strong".equals(strength) ? "сильная" : "moderate".equals(strength) ? "умеренная" : "слабая";
        String directionRu = "positive".equals(direction) ? "положительная" : "отрицательная";
        return "Обнаружена " + strengthRu + " " + directionRu + " связь (r=" + r + ", n=" + sampleSize
                + " дн. за " + windowDays + "): " + directionText + ".";
    }

    private double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
