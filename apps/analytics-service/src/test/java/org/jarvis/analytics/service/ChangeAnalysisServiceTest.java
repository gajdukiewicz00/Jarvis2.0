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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChangeAnalysisServiceTest {

    @Mock
    private LifeTrackerClient lifeTrackerClient;

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-13T12:00:00Z"), ZoneOffset.UTC);

    private ChangeAnalysisService changeAnalysisService;

    @BeforeEach
    void setUp() {
        TrendSeriesService trendSeriesService = new TrendSeriesService(lifeTrackerClient, clock);
        changeAnalysisService = new ChangeAnalysisService(trendSeriesService, clock);
    }

    @Test
    void whatChangedComparesCurrentWeekAgainstPreviousWeek() {
        stubTwoWeeksOfData();

        Map<String, Object> result = changeAnalysisService.whatChanged();

        assertEquals(Map.of("from", "2026-03-07", "to", "2026-03-13"), result.get("currentWeek"));
        assertEquals(Map.of("from", "2026-02-28", "to", "2026-03-06"), result.get("previousWeek"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) result.get("changes");
        assertEquals(3, changes.size());

        Map<String, Object> sleep = findByMetric(changes, "sleepAvgHours");
        assertEquals(8.0, sleep.get("previous"));
        assertEquals(6.0, sleep.get("current"));
        assertEquals("WORSE", sleep.get("verdict"));

        Map<String, Object> work = findByMetric(changes, "workHoursTotal");
        assertEquals(35.0, work.get("previous"));
        assertEquals(49.0, work.get("current"));
        assertEquals("WORSE", work.get("verdict"));

        Map<String, Object> spend = findByMetric(changes, "spendTotal");
        assertEquals(140.0, spend.get("previous"));
        assertEquals(280.0, spend.get("current"));
        assertEquals("WORSE", spend.get("verdict"));
    }

    @Test
    void whyWeekWentBadNarratesAllRegressionsWhenEverythingGotWorse() {
        stubTwoWeeksOfData();

        Map<String, Object> result = changeAnalysisService.whyWeekWentBad();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regressions = (List<Map<String, Object>>) result.get("regressions");
        assertEquals(3, regressions.size());
        String narrative = (String) result.get("narrative");
        assertTrue(narrative.startsWith("Похоже, неделя пошла хуже из-за:"));
        assertTrue(narrative.contains("сон"));
    }

    @Test
    void workChangeFlagsWorseForModerateOvertimeIncreaseBelowJumpThreshold() {
        // previous week total = 40.0h, current week total = 42.9h -> delta = +2.9h.
        // This is well above FLAT_DELTA_THRESHOLD (0.05) but below the old
        // "overtime jump" threshold (3.0h), so the buggy asymmetric logic
        // classified it as FLAT instead of WORSE.
        List<TimeRecordDTO> workRecords = List.of(
                workRecordSeconds(LocalDate.of(2026, 3, 2), 144000L),
                workRecordSeconds(LocalDate.of(2026, 3, 9), 154440L));
        when(lifeTrackerClient.getTimeRecords()).thenReturn(workRecords);
        when(lifeTrackerClient.getWellnessTrend("SLEEP")).thenReturn(List.of());
        when(lifeTrackerClient.getExpenses()).thenReturn(List.of());

        Map<String, Object> result = changeAnalysisService.whatChanged();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) result.get("changes");
        Map<String, Object> work = findByMetric(changes, "workHoursTotal");
        assertEquals(40.0, work.get("previous"));
        assertEquals(42.9, work.get("current"));
        assertEquals(2.9, work.get("delta"));
        assertEquals("WORSE", work.get("verdict"));
    }

    @Test
    void whyWeekWentBadReassuresWhenNothingGotWorse() {
        List<TimeRecordDTO> workRecords = new ArrayList<>();
        List<WellnessLogDTO> sleepLogs = new ArrayList<>();
        List<ExpenseDTO> expenses = new ArrayList<>();
        for (LocalDate day = LocalDate.of(2026, 2, 28); !day.isAfter(LocalDate.of(2026, 3, 13)); day = day.plusDays(1)) {
            workRecords.add(workRecord(day, 5));
            sleepLogs.add(new WellnessLogDTO("SLEEP", 8.0, day));
            expenses.add(expense(20, day));
        }
        when(lifeTrackerClient.getTimeRecords()).thenReturn(workRecords);
        when(lifeTrackerClient.getWellnessTrend("SLEEP")).thenReturn(sleepLogs);
        when(lifeTrackerClient.getExpenses()).thenReturn(expenses);

        Map<String, Object> result = changeAnalysisService.whyWeekWentBad();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regressions = (List<Map<String, Object>>) result.get("regressions");
        assertEquals(0, regressions.size());
        assertEquals("Ничего заметно не ухудшилось по сравнению с прошлой неделей.", result.get("narrative"));
    }

    private void stubTwoWeeksOfData() {
        List<TimeRecordDTO> workRecords = new ArrayList<>();
        List<WellnessLogDTO> sleepLogs = new ArrayList<>();
        List<ExpenseDTO> expenses = new ArrayList<>();

        for (LocalDate day = LocalDate.of(2026, 2, 28); !day.isAfter(LocalDate.of(2026, 3, 6)); day = day.plusDays(1)) {
            workRecords.add(workRecord(day, 5));
            sleepLogs.add(new WellnessLogDTO("SLEEP", 8.0, day));
            expenses.add(expense(20, day));
        }
        for (LocalDate day = LocalDate.of(2026, 3, 7); !day.isAfter(LocalDate.of(2026, 3, 13)); day = day.plusDays(1)) {
            workRecords.add(workRecord(day, 7));
            sleepLogs.add(new WellnessLogDTO("SLEEP", 6.0, day));
            expenses.add(expense(40, day));
        }

        when(lifeTrackerClient.getTimeRecords()).thenReturn(workRecords);
        when(lifeTrackerClient.getWellnessTrend("SLEEP")).thenReturn(sleepLogs);
        when(lifeTrackerClient.getExpenses()).thenReturn(expenses);
    }

    private Map<String, Object> findByMetric(List<Map<String, Object>> changes, String metric) {
        return changes.stream().filter(c -> metric.equals(c.get("metric"))).findFirst().orElseThrow();
    }

    private TimeRecordDTO workRecord(LocalDate day, int hours) {
        long durationSeconds = hours * 3600L;
        return workRecordSeconds(day, durationSeconds);
    }

    private TimeRecordDTO workRecordSeconds(LocalDate day, long durationSeconds) {
        return new TimeRecordDTO(1L, "Coding", "Work", day.atTime(9, 0), day.atTime(9, 0).plusSeconds(durationSeconds),
                durationSeconds);
    }

    private ExpenseDTO expense(double amount, LocalDate day) {
        ExpenseDTO dto = new ExpenseDTO();
        dto.setAmount(BigDecimal.valueOf(amount));
        dto.setType("EXPENSE");
        dto.setOccurredAt(day.atTime(10, 0));
        return dto;
    }
}
