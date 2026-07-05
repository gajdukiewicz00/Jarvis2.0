package org.jarvis.analytics.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.InsightDTO;
import org.jarvis.analytics.dto.OvertimeSummaryDTO;
import org.jarvis.analytics.dto.SleepSummaryDTO;
import org.jarvis.analytics.metrics.AnalyticsMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsightServiceTest {

    @Mock
    private LifeTrackerClient lifeTrackerClient;

    @Mock
    private AnalyticsService analyticsService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private SimpleMeterRegistry meterRegistry;
    private InsightService insightService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        insightService = new InsightService(lifeTrackerClient, analyticsService, clock, new AnalyticsMetrics(meterRegistry));
    }

    @Test
    void autoInsightsFlagsLowSleepOvertimeAndTheirCorrelation() {
        when(analyticsService.getSleepSummary(7)).thenReturn(sleepSummary(6.0, 5));
        when(analyticsService.getOvertimeSummary(7, 8)).thenReturn(overtimeSummary(5, 45.0));

        List<InsightDTO> insights = insightService.autoInsights();

        assertEquals(3, insights.size());
        assertEquals("LOW_SLEEP", insights.get(0).code());
        assertEquals("OVERTIME", insights.get(1).code());
        assertEquals("SLEEP_WORK_CORR", insights.get(2).code());
    }

    @Test
    void autoInsightsFlagsHealthySleepAndSpendConcentration() {
        when(analyticsService.getSleepSummary(7)).thenReturn(sleepSummary(8.0, 7));
        when(analyticsService.getOvertimeSummary(7, 8)).thenReturn(overtimeSummary(0, 40.0));
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense(new BigDecimal("100"), "Food", LocalDateTime.of(2026, 3, 10, 9, 0)),
                expense(new BigDecimal("20"), "Other", LocalDateTime.of(2026, 3, 11, 9, 0))));

        List<InsightDTO> insights = insightService.autoInsights();

        assertEquals(2, insights.size());
        assertEquals("SLEEP_OK", insights.get(0).code());
        assertEquals("SPEND_CONCENTRATION", insights.get(1).code());
        assertTrue(insights.get(1).detail().contains("Food"));
    }

    @Test
    void autoInsightsReturnsAllGoodWhenUpstreamSummariesAreUnavailable() {
        when(analyticsService.getSleepSummary(7)).thenThrow(new RuntimeException("life-tracker unavailable"));
        when(analyticsService.getOvertimeSummary(7, 8)).thenThrow(new RuntimeException("life-tracker unavailable"));

        List<InsightDTO> insights = insightService.autoInsights();

        assertEquals(1, insights.size());
        assertEquals("ALL_GOOD", insights.get(0).code());
    }

    @Test
    void dayScoreComputesScoreAndGradeFromSleepAndWork() {
        when(analyticsService.getSleepSummary(7)).thenReturn(sleepSummary(8.0, 7));
        when(analyticsService.getOvertimeSummary(7, 8)).thenReturn(overtimeSummary(5, 45.0));

        DayScoreDTO score = insightService.dayScore();

        assertEquals(80, score.score());
        assertEquals("A", score.grade());
        assertEquals(8.0, score.components().get("sleepAvgHours"));
        assertEquals(45.0, score.components().get("trackedWorkHours"));
        assertEquals(5, score.components().get("overtimeHours"));
    }

    @Test
    void dayScoreDefaultsToLowestGradeWhenSummariesAreUnavailable() {
        when(analyticsService.getSleepSummary(7)).thenThrow(new RuntimeException("boom"));
        when(analyticsService.getOvertimeSummary(7, 8)).thenThrow(new RuntimeException("boom"));

        DayScoreDTO score = insightService.dayScore();

        assertEquals(30, score.score());
        assertEquals("D", score.grade());
    }

    @Test
    void budgetForecastProjectsMonthEndSpendFromCurrentDailyRate() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense(new BigDecimal("100"), "Food", LocalDateTime.of(2026, 3, 1, 10, 0)),
                expense(new BigDecimal("200"), "Rent", LocalDateTime.of(2026, 3, 10, 10, 0)),
                expense(new BigDecimal("999"), "Ignored", LocalDateTime.of(2026, 2, 20, 10, 0))));

        Map<String, Object> forecast = insightService.budgetForecast();

        assertEquals("2026-03", forecast.get("month"));
        assertEquals(13, forecast.get("dayOfMonth"));
        assertEquals(31, forecast.get("daysInMonth"));
        assertEquals(300.0, forecast.get("spentSoFar"));
        assertEquals(23.08, forecast.get("dailyRate"));
        assertEquals(715.38, forecast.get("projectedMonthEnd"));
    }

    @Test
    void dailyReportCombinesScoreAndInsightsIntoReportText() {
        when(analyticsService.getSleepSummary(7)).thenReturn(sleepSummary(8.0, 7));
        when(analyticsService.getOvertimeSummary(7, 8)).thenReturn(overtimeSummary(0, 40.0));

        Map<String, Object> report = insightService.dailyReport();

        assertEquals(100, ((DayScoreDTO) report.get("score")).score());
        assertEquals("A", ((DayScoreDTO) report.get("score")).grade());
        @SuppressWarnings("unchecked")
        List<InsightDTO> insights = (List<InsightDTO>) report.get("insights");
        assertEquals(1, insights.size());
        assertEquals("SLEEP_OK", insights.get(0).code());
        assertEquals(
                "Сводка дня. Оценка 100/100 (A). Сон в норме: Средний сон 8.0 ч за 7 дн.",
                report.get("report"));
    }

    @Test
    void weeklyDigestReusesTheSameSevenDayAggregatesAsDailyReport() {
        SleepSummaryDTO sleep = sleepSummary(6.5, 5);
        OvertimeSummaryDTO overtime = overtimeSummary(3, 43.0);
        when(analyticsService.getSleepSummary(7)).thenReturn(sleep);
        when(analyticsService.getOvertimeSummary(7, 8)).thenReturn(overtime);

        Map<String, Object> digest = insightService.weeklyDigest();

        assertEquals(sleep, digest.get("sleep"));
        assertEquals(overtime, digest.get("overtime"));
        @SuppressWarnings("unchecked")
        List<InsightDTO> insights = (List<InsightDTO>) digest.get("insights");
        assertEquals(List.of("LOW_SLEEP", "OVERTIME", "SLEEP_WORK_CORR"),
                insights.stream().map(InsightDTO::code).toList());

        String text = (String) digest.get("digest");
        assertTrue(text.startsWith("Сводка недели."));
        assertTrue(text.contains("Средний сон 6.5 ч/сутки за 5 дн."));
        assertTrue(text.contains("Переработка +3 ч сверх нормы за неделю."));
        assertTrue(text.contains("Недосып: "));
        assertTrue(text.contains("Переработки: "));
        assertTrue(text.contains("Недосып ↔ переработки: "));

        // Verify getSleepSummary(7) / getOvertimeSummary(7, 8) are the same
        // aggregates dailyReport() relies on for its own insights + score.
        assertEquals(insightService.dailyReport().get("insights"), insights);
    }

    @Test
    void autoInsightsRecordsSuccessJobMetric() {
        when(analyticsService.getSleepSummary(7)).thenReturn(sleepSummary(8.0, 7));
        when(analyticsService.getOvertimeSummary(7, 8)).thenReturn(overtimeSummary(0, 30.0));

        insightService.autoInsights();

        assertEquals(1.0, meterRegistry
                .counter("analytics.jobs", "type", "auto_insights", "status", "success").count());
        assertEquals(1L, meterRegistry.find("analytics.job.duration").timer().count());
    }

    @Test
    void dayScoreRecordsSuccessJobMetricSeparatelyFromAutoInsights() {
        when(analyticsService.getSleepSummary(7)).thenReturn(sleepSummary(8.0, 7));
        when(analyticsService.getOvertimeSummary(7, 8)).thenReturn(overtimeSummary(0, 30.0));

        insightService.dayScore();

        assertEquals(1.0, meterRegistry
                .counter("analytics.jobs", "type", "day_score", "status", "success").count());
        assertEquals(0.0, meterRegistry
                .counter("analytics.jobs", "type", "auto_insights", "status", "success").count());
    }

    private SleepSummaryDTO sleepSummary(double averageHours, int daysSampled) {
        return new SleepSummaryDTO(averageHours, daysSampled, 7, averageHours * daysSampled);
    }

    private OvertimeSummaryDTO overtimeSummary(int overtimeHours, double trackedWorkHours) {
        return new OvertimeSummaryDTO(overtimeHours, trackedWorkHours, 8, 7);
    }

    private ExpenseDTO expense(BigDecimal amount, String category, LocalDateTime occurredAt) {
        return new ExpenseDTO(1L, "user-1", amount, "EUR", category, "desc", "EXPENSE", "merchant", occurredAt);
    }
}
