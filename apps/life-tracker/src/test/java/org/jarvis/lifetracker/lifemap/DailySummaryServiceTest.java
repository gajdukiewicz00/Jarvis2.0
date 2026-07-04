package org.jarvis.lifetracker.lifemap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailySummaryServiceTest {

    @Mock
    private InMemoryActivityStore activityStore;
    @Mock
    private CrossServiceClient cross;
    @Mock
    private ProactiveWarningEngine warningEngine;
    @Mock
    private FinanceTotals.Provider financeProvider;
    @Mock
    private DailySummaryService.SleepProvider sleepProvider;

    private DailySummaryService service;

    @BeforeEach
    void setUp() {
        service = new DailySummaryService(activityStore, cross, warningEngine, financeProvider, sleepProvider);
    }

    @Test
    void summariseAggregatesAllSourcesAndAppliesEngineWarnings() {
        LocalDate day = LocalDate.of(2026, 3, 10);
        Map<TimeCategory, Long> byCategory = Map.of(TimeCategory.WORK, 3600L, TimeCategory.REST, 600L);
        when(activityStore.secondsByCategoryForDay("user-1", day)).thenReturn(byCategory);
        when(financeProvider.totalsFor("user-1", day))
                .thenReturn(new FinanceTotals(new BigDecimal("500.00"), new BigDecimal("200.00"),
                        new BigDecimal("300.00")));
        when(cross.fetchTasks("user-1")).thenReturn(new CrossServiceClient.Tasks(3, 5));
        when(cross.fetchVisionIncidentCount("user-1")).thenReturn(2);
        when(cross.fetchMemoryWriteCount("user-1")).thenReturn(7);
        when(sleepProvider.lastNightHours("user-1", day)).thenReturn(6.5);

        LifeMapDtos.ProactiveWarning warning = new LifeMapDtos.ProactiveWarning(
                "warn-1", "TIME_WASTE", LifeMapDtos.ProactiveWarning.Severity.WARN, "msg",
                Map.of(), java.time.Instant.now());
        ArgumentCaptor<LifeMapDtos.DailySummary> previewCaptor =
                ArgumentCaptor.forClass(LifeMapDtos.DailySummary.class);
        when(warningEngine.evaluate(previewCaptor.capture())).thenReturn(List.of(warning));

        LifeMapDtos.DailySummary result = service.summarise("user-1", day);

        assertThat(result.date()).isEqualTo(day);
        assertThat(result.totalTrackedSeconds()).isEqualTo(4200L);
        assertThat(result.secondsByCategory()).isEqualTo(byCategory);
        assertThat(result.financeIncome()).isEqualByComparingTo("500.00");
        assertThat(result.financeExpense()).isEqualByComparingTo("200.00");
        assertThat(result.financeBudget()).isEqualByComparingTo("300.00");
        assertThat(result.tasksOpen()).isEqualTo(3);
        assertThat(result.tasksDoneToday()).isEqualTo(5);
        assertThat(result.sleepHours()).isEqualTo(6.5);
        assertThat(result.visionIncidentsLast24h()).isEqualTo(2);
        assertThat(result.jarvisLiveFeedCountLast24h()).isEqualTo(7);
        assertThat(result.warnings()).containsExactly(warning);

        // The preview passed into the engine must carry the same data but with no warnings yet.
        LifeMapDtos.DailySummary preview = previewCaptor.getValue();
        assertThat(preview.warnings()).isEmpty();
        assertThat(preview.totalTrackedSeconds()).isEqualTo(4200L);
        assertThat(preview.financeIncome()).isEqualByComparingTo("500.00");

        verify(cross).fetchTasks("user-1");
        verify(cross).fetchVisionIncidentCount("user-1");
        verify(cross).fetchMemoryWriteCount("user-1");
    }

    @Test
    void summariseWithNullDayDefaultsToToday() {
        LocalDate today = LocalDate.now();
        when(activityStore.secondsByCategoryForDay(eq("user-1"), any(LocalDate.class))).thenReturn(Map.of());
        when(financeProvider.totalsFor(eq("user-1"), any(LocalDate.class))).thenReturn(FinanceTotals.empty());
        when(cross.fetchTasks("user-1")).thenReturn(CrossServiceClient.Tasks.empty());
        when(sleepProvider.lastNightHours(eq("user-1"), any(LocalDate.class))).thenReturn(null);
        when(warningEngine.evaluate(any(LifeMapDtos.DailySummary.class))).thenReturn(List.of());

        LifeMapDtos.DailySummary result = service.summarise("user-1", null);

        assertThat(result.date()).isEqualTo(today);
        assertThat(result.totalTrackedSeconds()).isZero();
        assertThat(result.sleepHours()).isNull();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void summariseReturnsEmptyWarningsWhenEngineFindsNone() {
        LocalDate day = LocalDate.of(2026, 5, 1);
        when(activityStore.secondsByCategoryForDay("user-1", day)).thenReturn(Map.of());
        when(financeProvider.totalsFor("user-1", day)).thenReturn(FinanceTotals.empty());
        when(cross.fetchTasks("user-1")).thenReturn(CrossServiceClient.Tasks.empty());
        when(sleepProvider.lastNightHours("user-1", day)).thenReturn(null);
        when(warningEngine.evaluate(any(LifeMapDtos.DailySummary.class))).thenReturn(List.of());

        LifeMapDtos.DailySummary result = service.summarise("user-1", day);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.financeBudget()).isNull();
    }
}
