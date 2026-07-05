package org.jarvis.analytics.service;

import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.DayScoreDTO;
import org.jarvis.analytics.dto.TimeRecordDTO;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsistencyServiceTest {

    @Mock
    private LifeTrackerClient lifeTrackerClient;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private ConsistencyService consistencyService;

    @BeforeEach
    void setUp() {
        TrendSeriesService trendSeriesService = new TrendSeriesService(lifeTrackerClient, clock);
        consistencyService = new ConsistencyService(trendSeriesService);
    }

    @Test
    void studyWorkConsistencyScoresCoverageAndStabilityOverWindow() {
        List<TimeRecordDTO> records = new ArrayList<>();
        LocalDate[] activeDays = {
                LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 7),
                LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 10),
                LocalDate.of(2026, 3, 12), LocalDate.of(2026, 3, 13)
        };
        for (LocalDate day : activeDays) {
            records.add(workRecord(day.atTime(9, 0), 4 * 3600L));
        }
        when(lifeTrackerClient.getTimeRecords()).thenReturn(records);

        DayScoreDTO result = consistencyService.studyWorkConsistency(10);

        assertEquals(88, result.score());
        assertEquals("A", result.grade());
        assertEquals(8, result.components().get("activeDays"));
        assertEquals(10, result.components().get("windowDays"));
        assertEquals(80.0, result.components().get("coveragePct"));
        assertEquals(4.0, result.components().get("avgHoursOnActiveDays"));
        assertEquals(0.0, result.components().get("stdDevHours"));
    }

    @Test
    void studyWorkConsistencyScoresLowWhenNoActivityTracked() {
        when(lifeTrackerClient.getTimeRecords()).thenReturn(List.of());

        DayScoreDTO result = consistencyService.studyWorkConsistency(7);

        assertEquals(0, result.score());
        assertEquals("D", result.grade());
        assertEquals(0, result.components().get("activeDays"));
    }

    private TimeRecordDTO workRecord(LocalDateTime start, long durationSeconds) {
        return new TimeRecordDTO(1L, "Coding", "Work", start, start.plusSeconds(durationSeconds), durationSeconds);
    }
}
