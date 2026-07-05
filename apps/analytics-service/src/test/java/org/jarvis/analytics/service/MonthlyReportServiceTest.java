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
