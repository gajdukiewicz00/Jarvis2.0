package org.jarvis.analytics.service;

import org.jarvis.analytics.client.LifeTrackerClient;
import org.jarvis.analytics.dto.HabitStreakDTO;
import org.jarvis.analytics.dto.WellnessLogDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HabitStreakServiceTest {

    @Mock
    private LifeTrackerClient lifeTrackerClient;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private HabitStreakService habitStreakService;

    @BeforeEach
    void setUp() {
        habitStreakService = new HabitStreakService(lifeTrackerClient, clock);
    }

    @Test
    void habitStreaksComputeCurrentAndLongestRunsWithinWindow() {
        List<WellnessLogDTO> logs = List.of(
                habitLog("Meditation", 1.0, LocalDate.of(2026, 3, 4)),
                habitLog("Meditation", 1.0, LocalDate.of(2026, 3, 5)),
                habitLog("Meditation", 1.0, LocalDate.of(2026, 3, 6)),
                habitLog("Meditation", 1.0, LocalDate.of(2026, 3, 7)),
                habitLog("Meditation", 1.0, LocalDate.of(2026, 3, 8)),
                habitLog("Meditation", 0.0, LocalDate.of(2026, 3, 9)),
                habitLog("Meditation", 1.0, LocalDate.of(2026, 3, 10)),
                habitLog("Meditation", 1.0, LocalDate.of(2026, 3, 11)),
                habitLog("Meditation", 1.0, LocalDate.of(2026, 3, 12)),
                habitLog("Meditation", 1.0, LocalDate.of(2026, 3, 13)));
        when(lifeTrackerClient.getWellnessTrend("HABIT")).thenReturn(logs);

        List<HabitStreakDTO> result = habitStreakService.habitStreaks(10);

        assertEquals(1, result.size());
        HabitStreakDTO meditation = result.get(0);
        assertEquals("Meditation", meditation.habit());
        assertEquals(4, meditation.currentStreakDays());
        assertEquals(5, meditation.longestStreakDays());
        assertEquals(9, meditation.activeDays());
        assertEquals(10, meditation.windowDays());
        assertEquals(90.0, meditation.consistencyPct());
    }

    @Test
    void habitStreaksReturnsEmptyListWhenNoHabitLogsExist() {
        when(lifeTrackerClient.getWellnessTrend("HABIT")).thenReturn(List.of());

        assertEquals(List.of(), habitStreakService.habitStreaks(7));
    }

    @Test
    void habitStreaksBreaksCurrentStreakWhenTodayNotDone() {
        List<WellnessLogDTO> logs = List.of(
                habitLog("Reading", 1.0, LocalDate.of(2026, 3, 11)),
                habitLog("Reading", 1.0, LocalDate.of(2026, 3, 12)),
                habitLog("Reading", 0.0, LocalDate.of(2026, 3, 13)));
        when(lifeTrackerClient.getWellnessTrend("HABIT")).thenReturn(logs);

        List<HabitStreakDTO> result = habitStreakService.habitStreaks(7);

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).currentStreakDays());
        assertEquals(2, result.get(0).longestStreakDays());
    }

    private WellnessLogDTO habitLog(String habit, double value, LocalDate day) {
        return new WellnessLogDTO("HABIT", value, day, habit);
    }
}
