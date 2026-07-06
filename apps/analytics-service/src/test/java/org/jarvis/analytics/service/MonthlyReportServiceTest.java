package org.jarvis.analytics.service;

import org.jarvis.analytics.dto.AnomalyDTO;
import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.dto.ExpenseSummaryDTO;
import org.jarvis.analytics.dto.OvertimeSummaryDTO;
import org.jarvis.analytics.dto.SleepSummaryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthlyReportServiceTest {

    @Mock
    private AnalyticsService analyticsService;

    @Mock
    private InsightService insightService;

    @Mock
    private AnomalyDetectionService anomalyDetectionService;

    @Mock
    private ConsistencyService consistencyService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private MonthlyReportService monthlyReportService;

    @BeforeEach
    void setUp() {
        monthlyReportService = new MonthlyReportService(
                analyticsService, insightService, anomalyDetectionService, consistencyService, clock);
    }

    @Test
    void monthlyReportAssemblesSleepOvertimeConsistencyAndBudgetForecast() {
        when(analyticsService.getExpensesByCategory(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 13)))
                .thenReturn(List.of(
                        new ExpenseSummaryDTO("All", "Rent", new BigDecimal("500"), "EUR", 1),
                        new ExpenseSummaryDTO("All", "Food", new BigDecimal("200"), "EUR", 5)));
        when(analyticsService.getSleepSummary(30)).thenReturn(new SleepSummaryDTO(7.5, 25, 30, 187.5));
        when(analyticsService.getOvertimeSummary(30, 35))
                .thenReturn(new OvertimeSummaryDTO(10, 160.0, 35, 30));
        when(consistencyService.studyWorkConsistency(30))
                .thenReturn(new DayScoreDTO(75, "B", Map.<String, Object>of("activeDays", 20)));
        when(insightService.budgetForecast()).thenReturn(Map.of(
                "month", "2026-03", "spentSoFar", 700.0, "dayOfMonth", 13,
                "daysInMonth", 31, "dailyRate", 53.85, "projectedMonthEnd", 1669.35));

        Map<String, Object> report = monthlyReportService.monthlyReport();

        assertEquals("2026-03", report.get("month"));
        assertEquals(new BigDecimal("700"), report.get("spentSoFar"));
        @SuppressWarnings("unchecked")
        List<ExpenseSummaryDTO> topCategories = (List<ExpenseSummaryDTO>) report.get("topCategories");
        assertEquals(2, topCategories.size());
        assertEquals(75, ((DayScoreDTO) report.get("consistency")).score());
        String narrative = (String) report.get("report");
        assertTrue(narrative.contains("Отчёт за 2026-03"));
        assertTrue(narrative.contains("7.5"));
        assertTrue(narrative.contains("75/100 (B)"));
    }

    @Test
    void refinedOverspendForecastExcludesAnomalousDaysFromDailyRate() {
        when(insightService.budgetForecast()).thenReturn(Map.of(
                "month", "2026-03", "spentSoFar", 700.0, "dayOfMonth", 13,
                "daysInMonth", 31, "dailyRate", 53.85, "projectedMonthEnd", 1669.35));
        when(anomalyDetectionService.detectExpenseAnomalies(13, 2.0)).thenReturn(List.of(
                new AnomalyDTO("dailyExpenseTotal", LocalDate.of(2026, 3, 10), 300.0, 53.85, 80.0, "explanation")));

        Map<String, Object> refined = monthlyReportService.refinedOverspendForecast();

        assertEquals(33.33, refined.get("adjustedDailyRate"));
        assertEquals(1033.33, refined.get("adjustedProjectedMonthEnd"));
        @SuppressWarnings("unchecked")
        List<AnomalyDTO> excluded = (List<AnomalyDTO>) refined.get("excludedAnomalies");
        assertEquals(1, excluded.size());
    }

    @Test
    void refinedOverspendForecastExcludesPreviousMonthAnomaliesNearMonthStart() {
        // Day 2 of the month: Math.max(MIN_ANOMALY_WINDOW=3, dayOfMonth=2) widens the trailing
        // anomaly-detection window to 3 days, which reaches back into the previous month
        // (2026-03-31). spentSoFar/dayOfMonth are strictly current-month (April) figures, so a
        // previous-month anomaly must NOT be subtracted from the current month's total.
        Clock earlyMonthClock = Clock.fixed(Instant.parse("2026-04-02T12:00:00Z"), ZoneOffset.UTC);
        MonthlyReportService service = new MonthlyReportService(
                analyticsService, insightService, anomalyDetectionService, consistencyService, earlyMonthClock);
        when(insightService.budgetForecast()).thenReturn(Map.of(
                "month", "2026-04", "spentSoFar", 100.0, "dayOfMonth", 2,
                "daysInMonth", 30, "dailyRate", 50.0, "projectedMonthEnd", 1500.0));
        when(anomalyDetectionService.detectExpenseAnomalies(3, 2.0)).thenReturn(List.of(
                new AnomalyDTO("dailyExpenseTotal", LocalDate.of(2026, 3, 31), 1000.0, 50.0, 80.0, "prev-month"),
                new AnomalyDTO("dailyExpenseTotal", LocalDate.of(2026, 4, 1), 10.0, 50.0, 80.0, "current-month")));

        Map<String, Object> refined = service.refinedOverspendForecast();

        @SuppressWarnings("unchecked")
        List<AnomalyDTO> excluded = (List<AnomalyDTO>) refined.get("excludedAnomalies");
        assertEquals(1, excluded.size());
        assertEquals(LocalDate.of(2026, 4, 1), excluded.get(0).day());
        // adjustedSpent = max(0, 100.0 - 10.0) = 90.0; adjustedDailyRate = 90.0 / max(1, 2-1) = 90.0
        assertEquals(90.0, refined.get("adjustedDailyRate"));
        assertEquals(2700.0, refined.get("adjustedProjectedMonthEnd"));
    }

    @Test
    void refinedOverspendForecastMatchesNaiveWhenNoAnomalies() {
        when(insightService.budgetForecast()).thenReturn(Map.of(
                "month", "2026-03", "spentSoFar", 130.0, "dayOfMonth", 13,
                "daysInMonth", 31, "dailyRate", 10.0, "projectedMonthEnd", 310.0));
        when(anomalyDetectionService.detectExpenseAnomalies(13, 2.0)).thenReturn(List.of());

        Map<String, Object> refined = monthlyReportService.refinedOverspendForecast();

        assertEquals(10.0, refined.get("adjustedDailyRate"));
        assertEquals(310.0, refined.get("adjustedProjectedMonthEnd"));
        assertEquals("Аномальных дней трат не обнаружено — уточнённый прогноз совпадает с базовым.",
                refined.get("explanation"));
    }
}
