package org.jarvis.analytics.service;

import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.CalendarEventDTO;
import org.jarvis.analytics.dto.CalendarStatisticsDTO;
import org.jarvis.analytics.dto.ChartDataDTO;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.ExpenseSummaryDTO;
import org.jarvis.analytics.dto.OvertimeSummaryDTO;
import org.jarvis.analytics.dto.SleepSummaryDTO;
import org.jarvis.analytics.dto.TimeRecordDTO;
import org.jarvis.analytics.dto.TimeStatisticsDTO;
import org.jarvis.analytics.dto.WellnessLogDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private LifeTrackerClient lifeTrackerClient;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        analyticsService = new AnalyticsService(lifeTrackerClient, clock);
    }

    @Test
    void getSleepSummaryAveragesDailySleepTotalsWithinTrailingWindow() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                sleepRecord("Sleep", "Health", LocalDateTime.of(2026, 3, 13, 0, 0), 8 * 3600L),
                sleepRecord("Nap", "Rest", LocalDateTime.of(2026, 3, 12, 13, 0), 2 * 3600L),
                sleepRecord("Sleep", "Rest", LocalDateTime.of(2026, 3, 12, 0, 0), 6 * 3600L),
                workRecord("Coding", "Work", LocalDateTime.of(2026, 3, 13, 9, 0), 4 * 3600L),
                sleepRecord("Sleep", "Health", LocalDateTime.of(2026, 2, 20, 0, 0), 7 * 3600L)));

        SleepSummaryDTO result = analyticsService.getSleepSummary(14);

        assertEquals(14, result.getTrailingDays());
        assertEquals(2, result.getDaysSampled());
        assertEquals(16.0, result.getTotalSleepHours());
        assertEquals(8.0, result.getAverageHours());
    }

    @Test
    void getSleepSummaryReturnsNoDataWhenWindowHasNoSleepRecords() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                workRecord("Coding", "Work", LocalDateTime.of(2026, 3, 13, 9, 0), 4 * 3600L)));

        SleepSummaryDTO result = analyticsService.getSleepSummary(7);

        assertEquals(0, result.getDaysSampled());
        assertEquals(0.0, result.getTotalSleepHours());
        assertNull(result.getAverageHours());
    }

    @Test
    void getSleepSummaryUsesWellnessSleepLogsAsAuthoritativeSource() {
        // Sleep logged via /wellness/health-entry (phone Health Connect) — must be
        // counted even though it is NOT a time-record. Was previously invisible.
        when(lifeTrackerClient.getWellnessTrend("SLEEP")).thenReturn(List.of(
                sleepLog(7.5, LocalDate.of(2026, 3, 13)),
                sleepLog(6.0, LocalDate.of(2026, 3, 12)),
                sleepLog(8.0, LocalDate.of(2026, 2, 20)))); // outside the 14-day window

        SleepSummaryDTO result = analyticsService.getSleepSummary(14);

        assertEquals(2, result.getDaysSampled());
        assertEquals(13.5, result.getTotalSleepHours());
        assertEquals(6.75, result.getAverageHours());
    }

    @Test
    void getOvertimeSummaryComputesTrackedWorkHoursAndExcessAboveBaseline() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                workRecord("Coding", "Work", LocalDateTime.of(2026, 3, 13, 9, 0), 9 * 3600L),
                workRecord("Planning", "Focus", LocalDateTime.of(2026, 3, 12, 9, 0), 9 * 3600L),
                workRecord("Meetings", "Work", LocalDateTime.of(2026, 3, 11, 9, 0), 8 * 3600L),
                workRecord("Debugging", "Work", LocalDateTime.of(2026, 3, 10, 9, 0), 10 * 3600L),
                workRecord("Review", "Study", LocalDateTime.of(2026, 3, 9, 9, 0), 10 * 3600L),
                sleepRecord("Sleep", "Health", LocalDateTime.of(2026, 3, 13, 0, 0), 8 * 3600L)));

        OvertimeSummaryDTO result = analyticsService.getOvertimeSummary(7, 40);

        assertEquals(7, result.getTrailingDays());
        assertEquals(40, result.getBaselineHours());
        assertEquals(46.0, result.getTrackedWorkHours());
        assertEquals(6, result.getOvertimeHours());
    }

    @Test
    void getSleepSummaryThrowsWhenTrailingDaysIsNotPositive() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> analyticsService.getSleepSummary(0));

        assertEquals("trailingDays must be greater than zero", ex.getMessage());
    }

    @Test
    void getOvertimeSummaryThrowsWhenBaselineHoursIsNotPositive() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> analyticsService.getOvertimeSummary(7, 0));

        assertEquals("baselineHours must be greater than zero", ex.getMessage());
    }

    @Test
    void getOvertimeSummaryThrowsWhenTrailingDaysIsNotPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> analyticsService.getOvertimeSummary(-1, 40));
    }

    @Test
    void getSleepSummaryIgnoresWellnessEntriesWithNullValueOrDayOrOutsideWindow() {
        when(lifeTrackerClient.getWellnessTrend("SLEEP")).thenReturn(List.of(
                new WellnessLogDTO("SLEEP", null, LocalDate.of(2026, 3, 13)), // null numericValue -> skipped
                new WellnessLogDTO("SLEEP", 5.0, null), // null day -> skipped
                new WellnessLogDTO("SLEEP", 9.0, LocalDate.of(2026, 2, 1)), // before cutoff -> skipped
                new WellnessLogDTO("SLEEP", 9.0, LocalDate.of(2026, 3, 20)) // after today -> skipped
        ));

        SleepSummaryDTO result = analyticsService.getSleepSummary(7);

        assertEquals(0, result.getDaysSampled());
        assertEquals(0.0, result.getTotalSleepHours());
        assertNull(result.getAverageHours());
    }

    @Test
    void getSleepSummaryWellnessLogOverridesLegacyTimeRecordEstimateForSameDay() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                sleepRecord("Sleep", "Health", LocalDateTime.of(2026, 3, 13, 0, 0), 4 * 3600L)));
        when(lifeTrackerClient.getWellnessTrend("SLEEP")).thenReturn(List.of(
                sleepLog(9.0, LocalDate.of(2026, 3, 13))));

        SleepSummaryDTO result = analyticsService.getSleepSummary(7);

        assertEquals(1, result.getDaysSampled());
        assertEquals(9.0, result.getTotalSleepHours());
    }

    @Test
    void getOvertimeSummaryExcludesFutureDatedRecords() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                workRecord("Coding", "Work", LocalDateTime.of(2026, 3, 14, 9, 0), 5 * 3600L), // future vs. clock "today"
                workRecord("Coding", "Work", LocalDateTime.of(2026, 3, 13, 9, 0), 5 * 3600L)));

        OvertimeSummaryDTO result = analyticsService.getOvertimeSummary(7, 40);

        assertEquals(5.0, result.getTrackedWorkHours());
    }

    @Test
    void getOvertimeSummaryIgnoresRecordsWithoutDurationOrStartTime() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                new TimeRecordDTO(3L, "Coding", "Work", null, null, null),
                new TimeRecordDTO(4L, "Coding", "Work", LocalDateTime.of(2026, 3, 13, 9, 0), null, 0L)));

        OvertimeSummaryDTO result = analyticsService.getOvertimeSummary(7, 40);

        assertEquals(0.0, result.getTrackedWorkHours());
    }

    @Test
    void getExpensesByMonthFiltersNonExpenseTypesAndDateRangeAndDefaultsMissingCurrency() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense("100.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2026, 1, 5, 10, 0)),
                expense("50.00", null, "Food", null, LocalDateTime.of(2026, 1, 15, 10, 0)), // null type treated as expense, null currency -> EUR
                expense("999.00", "USD", "Salary", "INCOME", LocalDateTime.of(2026, 1, 10, 10, 0)), // excluded: not an expense
                expense("10.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2025, 12, 1, 10, 0)))); // excluded by date range

        List<ExpenseSummaryDTO> result = analyticsService.getExpensesByMonth(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertEquals(1, result.size());
        ExpenseSummaryDTO january = result.get(0);
        assertEquals("2026-01", january.getPeriod());
        assertEquals(0, new BigDecimal("150.00").compareTo(january.getTotalAmount()));
        assertEquals(2, january.getCount());
    }

    @Test
    void getExpensesByMonthWithoutDateRangeIncludesAllExpenses() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense("20.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2026, 2, 5, 10, 0)),
                expense("30.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2026, 3, 5, 10, 0))));

        List<ExpenseSummaryDTO> result = analyticsService.getExpensesByMonth(null, null);

        assertEquals(2, result.size());
        // sorted descending by period
        assertEquals("2026-03", result.get(0).getPeriod());
        assertEquals("2026-02", result.get(1).getPeriod());
    }

    @Test
    void getExpensesByCategoryGroupsAndDefaultsMissingCategoryAndSortsDescendingByTotal() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense("10.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2026, 1, 5, 10, 0)),
                expense("40.00", "USD", null, "EXPENSE", LocalDateTime.of(2026, 1, 6, 10, 0))));

        List<ExpenseSummaryDTO> result = analyticsService.getExpensesByCategory(null, null);

        assertEquals(2, result.size());
        assertEquals("Uncategorized", result.get(0).getCategory());
        assertEquals(0, new BigDecimal("40.00").compareTo(result.get(0).getTotalAmount()));
        assertEquals("Food", result.get(1).getCategory());
    }

    @Test
    void getExpensesByCategoryAppliesDateRangeFilter() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense("10.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2026, 1, 5, 10, 0)),
                expense("20.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2026, 5, 5, 10, 0))));

        List<ExpenseSummaryDTO> result = analyticsService.getExpensesByCategory(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertEquals(1, result.size());
        assertEquals(0, new BigDecimal("10.00").compareTo(result.get(0).getTotalAmount()));
    }

    @Test
    void getExpenseTrendForWeekPeriodReturnsLineChartGroupedByWeek() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense("10.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2026, 1, 5, 10, 0))));

        ChartDataDTO result = analyticsService.getExpenseTrend("week", null, null);

        assertEquals("line", result.getType());
        assertEquals("Expense Trend", result.getTitle());
        assertEquals("Period", result.getXAxisLabel());
        assertEquals("Amount", result.getYAxisLabel());
        assertEquals(1, result.getLabels().size());
        assertEquals(1, result.getValues().size());
    }

    @Test
    void getExpenseTrendForYearPeriodReturnsLineChartGroupedByYear() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense("10.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2026, 1, 5, 10, 0)),
                expense("20.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2025, 1, 5, 10, 0))));

        ChartDataDTO result = analyticsService.getExpenseTrend("YEAR", null, null);

        assertEquals(2, result.getLabels().size());
        assertTrue(result.getLabels().contains("2026"));
        assertTrue(result.getLabels().contains("2025"));
    }

    @Test
    void getExpenseTrendDefaultsToMonthForUnrecognizedPeriod() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense("10.00", "USD", "Food", "EXPENSE", LocalDateTime.of(2026, 1, 5, 10, 0))));

        ChartDataDTO result = analyticsService.getExpenseTrend("bogus", null, null);

        assertEquals(1, result.getLabels().size());
        assertEquals("2026-01", result.getLabels().get(0));
    }

    @Test
    void getTimeStatisticsGroupsByCategorySkipsNullDurationsAndDefaultsMissingCategory() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                new TimeRecordDTO(1L, "Coding", "Work", LocalDateTime.of(2026, 3, 1, 9, 0),
                        LocalDateTime.of(2026, 3, 1, 11, 0), 7200L),
                new TimeRecordDTO(2L, "Reading", null, LocalDateTime.of(2026, 3, 1, 9, 0),
                        LocalDateTime.of(2026, 3, 1, 10, 0), 3600L),
                new TimeRecordDTO(3L, "Untracked", "Work", LocalDateTime.of(2026, 3, 1, 9, 0), null, null))); // skipped: null duration

        List<TimeStatisticsDTO> result = analyticsService.getTimeStatistics();

        assertEquals(2, result.size());
        TimeStatisticsDTO work = result.stream().filter(dto -> "Work".equals(dto.getCategory())).findFirst().orElseThrow();
        assertEquals(2.0, work.getTotalDurationHours());
        assertEquals(1, work.getActivityCount());
        TimeStatisticsDTO uncategorized = result.stream()
                .filter(dto -> "Uncategorized".equals(dto.getCategory())).findFirst().orElseThrow();
        assertEquals(1.0, uncategorized.getTotalDurationHours());
    }

    @Test
    void getCalendarStatisticsCountsAllDayUpcomingAndPastEvents() {
        LocalDateTime now = LocalDateTime.now();
        when(lifeTrackerClient.getCalendarEvents()).thenReturn(List.of(
                new CalendarEventDTO(1L, "Past meeting", "desc", now.minusDays(1), now.minusDays(1).plusHours(1), false),
                new CalendarEventDTO(2L, "Future meeting", "desc", now.plusDays(1), now.plusDays(1).plusHours(1), false),
                new CalendarEventDTO(3L, "Holiday", "desc", now.plusDays(2), now.plusDays(3), true),
                new CalendarEventDTO(4L, "No start time", "desc", null, null, false)));

        CalendarStatisticsDTO result = analyticsService.getCalendarStatistics();

        assertEquals(4, result.getTotalEvents());
        assertEquals(2, result.getUpcomingEvents());
        assertEquals(1, result.getPastEvents());
        assertEquals(1, result.getAllDayEvents());
    }

    @Test
    void getCalendarStatisticsReturnsZeroesWhenNoEvents() {
        when(lifeTrackerClient.getCalendarEvents()).thenReturn(List.of());

        CalendarStatisticsDTO result = analyticsService.getCalendarStatistics();

        assertEquals(0, result.getTotalEvents());
        assertEquals(0, result.getUpcomingEvents());
        assertEquals(0, result.getPastEvents());
        assertEquals(0, result.getAllDayEvents());
    }

    @Test
    void getSleepSummaryIgnoresNonSleepAndUntaggedRecords() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                new TimeRecordDTO(5L, null, null, LocalDateTime.of(2026, 3, 13, 0, 0), null, 3600L)));

        SleepSummaryDTO result = analyticsService.getSleepSummary(7);

        assertEquals(0, result.getDaysSampled());
        assertFalse(result.getTotalSleepHours() > 0);
    }

    private ExpenseDTO expense(String amount, String currency, String category, String type, LocalDateTime occurredAt) {
        ExpenseDTO dto = new ExpenseDTO();
        dto.setAmount(new BigDecimal(amount));
        dto.setCurrency(currency);
        dto.setCategory(category);
        dto.setType(type);
        dto.setOccurredAt(occurredAt);
        return dto;
    }

    private TimeRecordDTO sleepRecord(String activity, String category, LocalDateTime startTime, long durationSeconds) {
        return new TimeRecordDTO(1L, activity, category, startTime, startTime.plusSeconds(durationSeconds), durationSeconds);
    }

    private TimeRecordDTO workRecord(String activity, String category, LocalDateTime startTime, long durationSeconds) {
        return new TimeRecordDTO(2L, activity, category, startTime, startTime.plusSeconds(durationSeconds), durationSeconds);
    }

    private WellnessLogDTO sleepLog(double hours, LocalDate day) {
        return new WellnessLogDTO("SLEEP", hours, day);
    }
}
