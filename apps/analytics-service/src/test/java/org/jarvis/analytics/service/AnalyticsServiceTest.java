package org.jarvis.analytics.service;

import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.OvertimeSummaryDTO;
import org.jarvis.analytics.dto.SleepSummaryDTO;
import org.jarvis.analytics.dto.TimeRecordDTO;
import org.jarvis.analytics.dto.WellnessLogDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
