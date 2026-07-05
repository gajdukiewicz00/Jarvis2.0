package org.jarvis.analytics.service;

import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.CorrelationResultDTO;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorrelationServiceTest {

    @Mock
    private LifeTrackerClient lifeTrackerClient;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private CorrelationService correlationService;

    @BeforeEach
    void setUp() {
        TrendSeriesService trendSeriesService = new TrendSeriesService(lifeTrackerClient, clock);
        correlationService = new CorrelationService(trendSeriesService);
    }

    @Test
    void sleepProductivityCorrelationDetectsStrongPositiveRelationship() {
        when(lifeTrackerClient.getWellnessTrend("SLEEP")).thenReturn(List.of(
                sleepLog(5.0, LocalDate.of(2026, 3, 7)),
                sleepLog(6.0, LocalDate.of(2026, 3, 8)),
                sleepLog(7.0, LocalDate.of(2026, 3, 9)),
                sleepLog(8.0, LocalDate.of(2026, 3, 10)),
                sleepLog(9.0, LocalDate.of(2026, 3, 11)),
                sleepLog(9.5, LocalDate.of(2026, 3, 12)),
                sleepLog(10.0, LocalDate.of(2026, 3, 13))));
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                workRecord(LocalDate.of(2026, 3, 7).atTime(9, 0), 2 * 3600L),
                workRecord(LocalDate.of(2026, 3, 8).atTime(9, 0), 3 * 3600L),
                workRecord(LocalDate.of(2026, 3, 9).atTime(9, 0), 4 * 3600L),
                workRecord(LocalDate.of(2026, 3, 10).atTime(9, 0), 5 * 3600L),
                workRecord(LocalDate.of(2026, 3, 11).atTime(9, 0), 6 * 3600L),
                workRecord(LocalDate.of(2026, 3, 12).atTime(9, 0), 7 * 3600L),
                workRecord(LocalDate.of(2026, 3, 13).atTime(9, 0), 8 * 3600L)));

        CorrelationResultDTO result = correlationService.sleepProductivityCorrelation(7);

        assertEquals("sleepHours", result.metricA());
        assertEquals("workHours", result.metricB());
        assertEquals(7, result.sampleSize());
        assertEquals("strong", result.strength());
        assertEquals("positive", result.direction());
        assertTrue(result.coefficient() > 0.95);
    }

    @Test
    void sleepProductivityCorrelationReturnsNoneWhenTooFewOverlappingDays() {
        when(lifeTrackerClient.getWellnessTrend("SLEEP")).thenReturn(List.of(
                sleepLog(7.0, LocalDate.of(2026, 3, 13))));
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of(
                workRecord(LocalDate.of(2026, 3, 13).atTime(9, 0), 4 * 3600L)));

        CorrelationResultDTO result = correlationService.sleepProductivityCorrelation(7);

        assertEquals("none", result.strength());
        assertEquals("none", result.direction());
        assertEquals(1, result.sampleSize());
        assertNull(result.coefficient());
    }

    private WellnessLogDTO sleepLog(double hours, LocalDate day) {
        return new WellnessLogDTO("SLEEP", hours, day);
    }

    private TimeRecordDTO workRecord(LocalDateTime start, long durationSeconds) {
        return new TimeRecordDTO(1L, "Coding", "Work", start, start.plusSeconds(durationSeconds), durationSeconds);
    }
}
