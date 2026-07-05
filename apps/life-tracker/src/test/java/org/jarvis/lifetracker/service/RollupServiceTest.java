package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.dto.FinanceSummaryDTO;
import org.jarvis.lifetracker.dto.RollupDTO;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RollupServiceTest {

    @Mock
    private FinanceService financeService;
    @Mock
    private WellnessLogRepository wellnessLogRepository;

    private RollupService rollupService;

    @BeforeEach
    void setUp() {
        rollupService = new RollupService(financeService, wellnessLogRepository);
    }

    private WellnessLog metric(WellnessType type, LocalDate day, double value) {
        WellnessLog log = new WellnessLog();
        log.setType(type);
        log.setDay(day);
        log.setNumericValue(value);
        return log;
    }

    private WellnessLog habit(LocalDate day, String name, double value) {
        WellnessLog log = new WellnessLog();
        log.setType(WellnessType.HABIT);
        log.setDay(day);
        log.setTextValue(name);
        log.setNumericValue(value);
        return log;
    }

    @Test
    void weeklyRollupUsesMondayToSundayWindowAndAggregatesFinanceAndWellness() {
        LocalDate wednesday = LocalDate.of(2026, 3, 11); // a Wednesday
        LocalDate monday = LocalDate.of(2026, 3, 9);
        LocalDate sunday = LocalDate.of(2026, 3, 15);

        when(financeService.summarizeRange(eq("user-1"), eq(monday), eq(sunday), eq("WEEK")))
                .thenReturn(new FinanceSummaryDTO("WEEK", new BigDecimal("100.00"), new BigDecimal("40.00"),
                        "EUR", Map.of("Food", new BigDecimal("40.00"))));
        when(financeService.listTransactions(eq("user-1"), any(LocalDateTime.class), any(LocalDateTime.class),
                eq(null), eq(null)))
                .thenReturn(List.of(new ExpenseDTO(), new ExpenseDTO()));
        when(wellnessLogRepository.findByUserIdAndDayBetweenOrderByLoggedAtAsc("user-1", monday, sunday))
                .thenReturn(List.of(
                        metric(WellnessType.WEIGHT, monday, 80.0),
                        metric(WellnessType.WEIGHT, sunday, 78.0),
                        habit(monday, "Meditate", 1),
                        habit(sunday, "Meditate", 0)));

        RollupDTO rollup = rollupService.weeklyRollup("user-1", wednesday);

        assertThat(rollup.period()).isEqualTo("WEEK");
        assertThat(rollup.startDate()).isEqualTo(monday);
        assertThat(rollup.endDate()).isEqualTo(sunday);
        assertThat(rollup.totalIncome()).isEqualByComparingTo("100.00");
        assertThat(rollup.totalExpense()).isEqualByComparingTo("40.00");
        assertThat(rollup.expenseByCategory()).containsEntry("Food", new BigDecimal("40.00"));
        assertThat(rollup.wellnessAverages()).containsEntry(WellnessType.WEIGHT, 79.0);
        assertThat(rollup.habitCompletionRate()).isEqualTo(0.5);
        assertThat(rollup.wellnessEntryCount()).isEqualTo(4);
        assertThat(rollup.transactionCount()).isEqualTo(2);
    }

    @Test
    void weeklyRollupDefaultsToCurrentWeekWhenDateIsNull() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
        LocalDate sunday = monday.plusDays(6);
        when(financeService.summarizeRange(eq("user-1"), eq(monday), eq(sunday), eq("WEEK")))
                .thenReturn(new FinanceSummaryDTO("WEEK", BigDecimal.ZERO, BigDecimal.ZERO, "EUR", Map.of()));
        when(financeService.listTransactions(eq("user-1"), any(), any(), eq(null), eq(null))).thenReturn(List.of());
        when(wellnessLogRepository.findByUserIdAndDayBetweenOrderByLoggedAtAsc("user-1", monday, sunday))
                .thenReturn(List.of());

        RollupDTO rollup = rollupService.weeklyRollup("user-1", null);

        assertThat(rollup.startDate()).isEqualTo(monday);
        assertThat(rollup.endDate()).isEqualTo(sunday);
        assertThat(rollup.habitCompletionRate()).isNull();
        assertThat(rollup.wellnessAverages()).isEmpty();
    }

    @Test
    void monthlyRollupUsesFullCalendarMonth() {
        YearMonth month = YearMonth.of(2026, 4);
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        when(financeService.summarizeRange(eq("user-1"), eq(start), eq(end), eq("MONTH")))
                .thenReturn(new FinanceSummaryDTO("MONTH", new BigDecimal("2000.00"), new BigDecimal("1200.00"),
                        "EUR", Map.of()));
        when(financeService.listTransactions(eq("user-1"), any(), any(), eq(null), eq(null))).thenReturn(List.of());
        when(wellnessLogRepository.findByUserIdAndDayBetweenOrderByLoggedAtAsc("user-1", start, end))
                .thenReturn(List.of(metric(WellnessType.SLEEP, start, 7.0), metric(WellnessType.SLEEP, end, 8.0)));

        RollupDTO rollup = rollupService.monthlyRollup("user-1", month);

        assertThat(rollup.period()).isEqualTo("MONTH");
        assertThat(rollup.startDate()).isEqualTo(start);
        assertThat(rollup.endDate()).isEqualTo(end);
        assertThat(rollup.wellnessAverages()).containsEntry(WellnessType.SLEEP, 7.5);
    }
}
