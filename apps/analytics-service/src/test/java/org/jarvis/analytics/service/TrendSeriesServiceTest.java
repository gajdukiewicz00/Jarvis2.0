package org.jarvis.analytics.service;

import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.ExpenseDTO;
import org.jarvis.analytics.dto.TimeRecordDTO;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrendSeriesServiceTest {

    @Mock
    private LifeTrackerClient lifeTrackerClient;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private TrendSeriesService trendSeriesService;

    @BeforeEach
    void setUp() {
        trendSeriesService = new TrendSeriesService(lifeTrackerClient, clock);
    }

    @Test
    void dailySleepHoursPrefersWellnessLogOverLegacyTimeRecordEstimate() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                sleepRecord(LocalDate.of(2026, 3, 12).atTime(0, 0), 5 * 3600L),
                workRecord(LocalDate.of(2026, 3, 13).atTime(9, 0), 4 * 3600L)));
        when(lifeTrackerClient.getWellnessTrend("SLEEP")).thenReturn(List.of(
                new WellnessLogDTO("SLEEP", 8.0, LocalDate.of(2026, 3, 12)),
                new WellnessLogDTO("SLEEP", 7.0, LocalDate.of(2026, 3, 13))));

        Map<LocalDate, Double> result = trendSeriesService.dailySleepHours(7);

        assertEquals(8.0, result.get(LocalDate.of(2026, 3, 12)));
        assertEquals(7.0, result.get(LocalDate.of(2026, 3, 13)));
        assertEquals(2, result.size());
    }

    @Test
    void dailyWorkHoursSumsWorkTaggedRecordsPerDayAndIgnoresSleep() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                workRecord(LocalDate.of(2026, 3, 12).atTime(9, 0), 3 * 3600L),
                workRecord(LocalDate.of(2026, 3, 12).atTime(14, 0), 2 * 3600L),
                sleepRecord(LocalDate.of(2026, 3, 12).atTime(0, 0), 6 * 3600L)));

        Map<LocalDate, Double> result = trendSeriesService.dailyWorkHours(7);

        assertEquals(5.0, result.get(LocalDate.of(2026, 3, 12)));
        assertEquals(1, result.size());
    }

    @Test
    void dailyExpenseTotalsSumsExpenseTypeOnlyWithinWindow() {
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of(
                expense(20, "EXPENSE", LocalDate.of(2026, 3, 13)),
                expense(1000, "INCOME", LocalDate.of(2026, 3, 13)),
                expense(50, "EXPENSE", LocalDate.of(2026, 2, 1))));

        Map<LocalDate, Double> result = trendSeriesService.dailyExpenseTotals(7);

        assertEquals(20.0, result.get(LocalDate.of(2026, 3, 13)));
        assertEquals(1, result.size());
    }

    private TimeRecordDTO sleepRecord(LocalDateTime start, long durationSeconds) {
        return new TimeRecordDTO(1L, "Sleep", "Rest", start, start.plusSeconds(durationSeconds), durationSeconds);
    }

    private TimeRecordDTO workRecord(LocalDateTime start, long durationSeconds) {
        return new TimeRecordDTO(2L, "Coding", "Work", start, start.plusSeconds(durationSeconds), durationSeconds);
    }

    private ExpenseDTO expense(double amount, String type, LocalDate day) {
        ExpenseDTO dto = new ExpenseDTO();
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setType(type);
        dto.setOccurredAt(day.atTime(10, 0));
        return dto;
    }
}
