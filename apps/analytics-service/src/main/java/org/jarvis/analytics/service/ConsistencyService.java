package org.jarvis.analytics.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.util.StatsMath;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Study/work consistency score: rewards how many days out of the trailing
 * window had tracked work/study activity, penalized for how erratic the daily
 * hours are (coefficient of variation).
 */
@Service
@RequiredArgsConstructor
public class ConsistencyService {

    private static final double COVERAGE_WEIGHT = 60.0;
    private static final double ACTIVITY_BONUS = 40.0;
    private static final double MAX_VARIABILITY_PENALTY = 40.0;

    private final TrendSeriesService trendSeriesService;

    public DayScoreDTO studyWorkConsistency(int windowDays) {
        Map<LocalDate, Double> work = trendSeriesService.dailyWorkHours(windowDays);
        int totalDays = Math.max(1, windowDays);
        int activeDays = work.size();
        double coveragePct = (double) activeDays / totalDays;

        List<Double> hours = new ArrayList<>(work.values());
        double avgHours = StatsMath.mean(hours);
        double stdDev = StatsMath.stdDev(hours);
        double coefficientOfVariation = avgHours > 0 ? stdDev / avgHours : 0.0;
        double variabilityPenalty = Math.min(MAX_VARIABILITY_PENALTY, coefficientOfVariation * MAX_VARIABILITY_PENALTY);

        double raw = coveragePct * COVERAGE_WEIGHT + (avgHours > 0 ? ACTIVITY_BONUS : 0.0) - variabilityPenalty;
        int score = (int) Math.round(Math.max(0, Math.min(100, raw)));
        String grade = score >= 80 ? "A" : score >= 60 ? "B" : score >= 40 ? "C" : "D";

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("activeDays", activeDays);
        components.put("windowDays", totalDays);
        components.put("coveragePct", round(coveragePct * 100));
        components.put("avgHoursOnActiveDays", round(avgHours));
        components.put("stdDevHours", round(stdDev));
        return new DayScoreDTO(score, grade, components);
    }

    private double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
