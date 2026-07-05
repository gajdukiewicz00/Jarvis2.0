package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.jarvis.lifetracker.dto.HabitStreakDTO;
import org.jarvis.lifetracker.dto.WellnessSummaryDTO;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WellnessServiceTest {

    @Mock
    private WellnessLogRepository repository;

    private WellnessService wellnessService;

    @BeforeEach
    void setUp() {
        wellnessService = new WellnessService(repository);
    }

    private WellnessLog habitEntry(LocalDate day, String name, double value) {
        WellnessLog log = new WellnessLog();
        log.setType(WellnessType.HABIT);
        log.setTextValue(name);
        log.setNumericValue(value);
        log.setDay(day);
        log.setLoggedAt(day.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        return log;
    }

    private WellnessLog metricEntry(WellnessType type, LocalDate day, double value) {
        WellnessLog log = new WellnessLog();
        log.setType(type);
        log.setNumericValue(value);
        log.setDay(day);
        log.setLoggedAt(day.atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        return log;
    }

    @Test
    void habitStreakReturnsZeroesWhenNeverLogged() {
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.HABIT)).thenReturn(List.of());

        HabitStreakDTO streak = wellnessService.habitStreak("user-1", "Meditate");

        assertThat(streak.currentStreak()).isZero();
        assertThat(streak.longestStreak()).isZero();
        assertThat(streak.lastLoggedDay()).isNull();
        assertThat(streak.completedToday()).isFalse();
        assertThat(streak.totalCheckIns()).isZero();
    }

    @Test
    void habitStreakCountsConsecutiveDaysEndingToday() {
        LocalDate today = LocalDate.now();
        List<WellnessLog> logs = List.of(
                habitEntry(today.minusDays(4), "Meditate", 1),
                habitEntry(today.minusDays(3), "Meditate", 1),
                habitEntry(today.minusDays(2), "Meditate", 1),
                habitEntry(today.minusDays(1), "Meditate", 1),
                habitEntry(today, "Meditate", 1));
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.HABIT)).thenReturn(logs);

        HabitStreakDTO streak = wellnessService.habitStreak("user-1", "meditate");

        assertThat(streak.currentStreak()).isEqualTo(5);
        assertThat(streak.longestStreak()).isEqualTo(5);
        assertThat(streak.completedToday()).isTrue();
        assertThat(streak.lastLoggedDay()).isEqualTo(today);
    }

    @Test
    void habitStreakStaysAliveWithYesterdayGraceDay() {
        LocalDate today = LocalDate.now();
        List<WellnessLog> logs = List.of(
                habitEntry(today.minusDays(2), "Meditate", 1),
                habitEntry(today.minusDays(1), "Meditate", 1));
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.HABIT)).thenReturn(logs);

        HabitStreakDTO streak = wellnessService.habitStreak("user-1", "Meditate");

        assertThat(streak.currentStreak()).isEqualTo(2);
        assertThat(streak.completedToday()).isFalse();
    }

    @Test
    void habitStreakBreaksAfterMissedDay() {
        LocalDate today = LocalDate.now();
        List<WellnessLog> logs = List.of(
                habitEntry(today.minusDays(5), "Meditate", 1),
                habitEntry(today.minusDays(4), "Meditate", 1),
                // gap: today-3 and today-2 missing entirely
                habitEntry(today.minusDays(1), "Meditate", 1),
                habitEntry(today, "Meditate", 1));
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.HABIT)).thenReturn(logs);

        HabitStreakDTO streak = wellnessService.habitStreak("user-1", "Meditate");

        assertThat(streak.currentStreak()).isEqualTo(2);
        assertThat(streak.longestStreak()).isEqualTo(2);
    }

    @Test
    void habitStreakIsZeroWhenLastCheckInIsStale() {
        LocalDate today = LocalDate.now();
        List<WellnessLog> logs = List.of(
                habitEntry(today.minusDays(10), "Meditate", 1),
                habitEntry(today.minusDays(9), "Meditate", 1));
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.HABIT)).thenReturn(logs);

        HabitStreakDTO streak = wellnessService.habitStreak("user-1", "Meditate");

        assertThat(streak.currentStreak()).isZero();
        assertThat(streak.longestStreak()).isEqualTo(2);
    }

    @Test
    void habitStreakTreatsSkippedDayAsNotDone() {
        LocalDate today = LocalDate.now();
        List<WellnessLog> logs = List.of(
                habitEntry(today.minusDays(2), "Meditate", 1),
                habitEntry(today.minusDays(1), "Meditate", 0), // explicitly skipped
                habitEntry(today, "Meditate", 1));
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.HABIT)).thenReturn(logs);

        HabitStreakDTO streak = wellnessService.habitStreak("user-1", "Meditate");

        assertThat(streak.currentStreak()).isEqualTo(1);
        assertThat(streak.longestStreak()).isEqualTo(1);
    }

    @Test
    void listHabitStreaksReturnsOneEntryPerDistinctHabitName() {
        LocalDate today = LocalDate.now();
        List<WellnessLog> logs = List.of(
                habitEntry(today, "Meditate", 1),
                habitEntry(today, "Exercise", 1),
                habitEntry(today.minusDays(1), "Meditate", 1));
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.HABIT)).thenReturn(logs);

        List<HabitStreakDTO> streaks = wellnessService.listHabitStreaks("user-1");

        assertThat(streaks).hasSize(2);
        assertThat(streaks).extracting(HabitStreakDTO::habitName).containsExactlyInAnyOrder("Meditate", "Exercise");
        HabitStreakDTO meditate = streaks.stream().filter(s -> s.habitName().equals("Meditate")).findFirst().orElseThrow();
        assertThat(meditate.currentStreak()).isEqualTo(2);
    }

    @Test
    void summaryReturnsEmptySummaryWhenNoEntriesInRange() {
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.WEIGHT)).thenReturn(List.of());

        WellnessSummaryDTO summary = wellnessService.summary(
                "user-1", WellnessType.WEIGHT, LocalDate.now().minusDays(7), LocalDate.now());

        assertThat(summary.entryCount()).isZero();
        assertThat(summary.average()).isNull();
    }

    @Test
    void summaryComputesAverageMinMaxAndLatest() {
        LocalDate today = LocalDate.now();
        List<WellnessLog> logs = List.of(
                metricEntry(WellnessType.WEIGHT, today.minusDays(2), 80.0),
                metricEntry(WellnessType.WEIGHT, today.minusDays(1), 79.0),
                metricEntry(WellnessType.WEIGHT, today, 78.5));
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.WEIGHT)).thenReturn(logs);

        WellnessSummaryDTO summary = wellnessService.summary(
                "user-1", WellnessType.WEIGHT, today.minusDays(7), today);

        assertThat(summary.entryCount()).isEqualTo(3);
        assertThat(summary.average()).isEqualTo((80.0 + 79.0 + 78.5) / 3);
        assertThat(summary.min()).isEqualTo(78.5);
        assertThat(summary.max()).isEqualTo(80.0);
        assertThat(summary.latest()).isEqualTo(78.5);
        assertThat(summary.latestDay()).isEqualTo(today);
    }

    @Test
    void summaryExcludesEntriesOutsideDateRange() {
        LocalDate today = LocalDate.now();
        List<WellnessLog> logs = List.of(
                metricEntry(WellnessType.STEPS, today.minusDays(20), 5000.0),
                metricEntry(WellnessType.STEPS, today.minusDays(1), 9000.0));
        when(repository.findByUserIdAndTypeOrderByLoggedAtAsc("user-1", WellnessType.STEPS)).thenReturn(logs);

        WellnessSummaryDTO summary = wellnessService.summary(
                "user-1", WellnessType.STEPS, today.minusDays(7), today);

        assertThat(summary.entryCount()).isEqualTo(1);
        assertThat(summary.average()).isEqualTo(9000.0);
    }
}
