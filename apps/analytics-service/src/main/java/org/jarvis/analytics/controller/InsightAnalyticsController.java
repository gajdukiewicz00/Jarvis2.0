package org.jarvis.analytics.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.analytics.dto.CorrelationResultDTO;
import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.dto.HabitStreakDTO;
import org.jarvis.analytics.dto.NlAnalyticsRequestDTO;
import org.jarvis.analytics.dto.NlAnalyticsResponseDTO;
import org.jarvis.analytics.service.AnomalyDetectionService;
import org.jarvis.analytics.service.ChangeAnalysisService;
import org.jarvis.analytics.service.ConsistencyService;
import org.jarvis.analytics.service.CorrelationService;
import org.jarvis.analytics.service.HabitStreakService;
import org.jarvis.analytics.service.MonthlyReportService;
import org.jarvis.analytics.service.NlAnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extended insight-engine endpoints on top of {@link InsightController}: monthly
 * reports, refined budget forecasts, correlation/consistency/streak analytics,
 * anomaly detection, week-over-week change explanations, and a guarded
 * natural-language analytics endpoint.
 */
@RestController
@RequestMapping("/api/v1/analytics/insights")
@RequiredArgsConstructor
public class InsightAnalyticsController {

    private final MonthlyReportService monthlyReportService;
    private final CorrelationService correlationService;
    private final ConsistencyService consistencyService;
    private final HabitStreakService habitStreakService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final ChangeAnalysisService changeAnalysisService;
    private final NlAnalyticsService nlAnalyticsService;

    @GetMapping("/monthly-report")
    public Map<String, Object> monthlyReport() {
        return monthlyReportService.monthlyReport();
    }

    @GetMapping("/forecast/refined")
    public Map<String, Object> refinedForecast() {
        return monthlyReportService.refinedOverspendForecast();
    }

    @GetMapping("/correlation/sleep-productivity")
    public CorrelationResultDTO sleepProductivityCorrelation(@RequestParam(defaultValue = "7") int days) {
        return correlationService.sleepProductivityCorrelation(days);
    }

    @GetMapping("/consistency")
    public DayScoreDTO consistency(@RequestParam(defaultValue = "30") int days) {
        return consistencyService.studyWorkConsistency(days);
    }

    @GetMapping("/habits/streaks")
    public List<HabitStreakDTO> habitStreaks(@RequestParam(defaultValue = "30") int days) {
        return habitStreakService.habitStreaks(days);
    }

    @GetMapping("/anomalies")
    public Map<String, Object> anomalies(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "2.0") double k) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expense", anomalyDetectionService.detectExpenseAnomalies(days, k));
        out.put("sleep", anomalyDetectionService.detectSleepAnomalies(days, k));
        out.put("work", anomalyDetectionService.detectWorkAnomalies(days, k));
        return out;
    }

    @GetMapping("/what-changed")
    public Map<String, Object> whatChanged() {
        return changeAnalysisService.whatChanged();
    }

    @GetMapping("/why-week-bad")
    public Map<String, Object> whyWeekWentBad() {
        return changeAnalysisService.whyWeekWentBad();
    }

    /** Guarded NL-analytics scaffold: routes a free-text question to llm-service when enabled, else a rule-based fallback. */
    @PostMapping("/ask")
    public NlAnalyticsResponseDTO ask(
            @RequestBody(required = false) NlAnalyticsRequestDTO request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String question = request == null ? null : request.question();
        return nlAnalyticsService.ask(userId, question);
    }
}
