package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.Budget;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.BudgetAlertDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetAlertServiceTest {

    private final BudgetAlertService service = new BudgetAlertService();

    private Budget budget(String category, String limit, String currency) {
        Budget b = new Budget();
        b.setCategory(category);
        b.setLimitAmount(new BigDecimal(limit));
        b.setCurrency(currency);
        return b;
    }

    private Expense expense(String category, String amount, LocalDateTime occurredAt) {
        Expense e = new Expense();
        e.setCategory(category);
        e.setAmount(new BigDecimal(amount));
        e.setType(TransactionType.EXPENSE);
        e.setOccurredAt(occurredAt);
        return e;
    }

    @Test
    void flagsOverBudgetWhenSpentExceedsLimit() {
        YearMonth month = YearMonth.of(2026, 3); // 31 days
        Budget foodBudget = budget("Food", "100.00", "EUR");
        Expense spent = expense("Food", "150.00", LocalDateTime.of(2026, 3, 10, 12, 0));
        LocalDate today = LocalDate.of(2026, 3, 15);

        List<BudgetAlertDTO> alerts = service.computeAlerts(month, List.of(foodBudget), List.of(spent), today);

        assertThat(alerts).hasSize(1);
        BudgetAlertDTO alert = alerts.get(0);
        assertThat(alert.isOverBudget()).isTrue();
        assertThat(alert.isAlert()).isTrue();
        assertThat(alert.getMessage()).contains("exceeded");
    }

    @Test
    void flagsAheadOfPaceWhenBudgetConsumedFasterThanMonthElapsed() {
        // 31-day month, 10 days elapsed (~32%), but 80% of the budget already spent.
        YearMonth month = YearMonth.of(2026, 3);
        Budget foodBudget = budget("Food", "100.00", "EUR");
        Expense spent = expense("Food", "80.00", LocalDateTime.of(2026, 3, 5, 12, 0));
        LocalDate today = LocalDate.of(2026, 3, 10);

        List<BudgetAlertDTO> alerts = service.computeAlerts(month, List.of(foodBudget), List.of(spent), today);

        BudgetAlertDTO alert = alerts.get(0);
        assertThat(alert.isOverBudget()).isFalse();
        assertThat(alert.isOverPace()).isTrue();
        assertThat(alert.isAlert()).isTrue();
        assertThat(alert.getMessage()).contains("ahead of budget pace");
        assertThat(alert.getPercentBudgetUsed()).isGreaterThan(alert.getPercentPeriodElapsed());
    }

    @Test
    void doesNotFlagWhenSpendingIsBehindOrOnPace() {
        // 31-day month, 20 days elapsed (~65%), only 10% of budget spent.
        YearMonth month = YearMonth.of(2026, 3);
        Budget foodBudget = budget("Food", "100.00", "EUR");
        Expense spent = expense("Food", "10.00", LocalDateTime.of(2026, 3, 2, 12, 0));
        LocalDate today = LocalDate.of(2026, 3, 20);

        List<BudgetAlertDTO> alerts = service.computeAlerts(month, List.of(foodBudget), List.of(spent), today);

        BudgetAlertDTO alert = alerts.get(0);
        assertThat(alert.isOverBudget()).isFalse();
        assertThat(alert.isOverPace()).isFalse();
        assertThat(alert.isAlert()).isFalse();
        assertThat(alert.getMessage()).contains("on track");
    }

    @Test
    void projectsFullPeriodSpendBasedOnCurrentPace() {
        YearMonth month = YearMonth.of(2026, 3); // 31 days
        Budget foodBudget = budget("Food", "500.00", "EUR");
        Expense spent = expense("Food", "100.00", LocalDateTime.of(2026, 3, 5, 0, 0));
        LocalDate today = LocalDate.of(2026, 3, 10); // 10 days elapsed

        List<BudgetAlertDTO> alerts = service.computeAlerts(month, List.of(foodBudget), List.of(spent), today);

        // projected = 100 * 31 / 10 = 310.00
        assertThat(alerts.get(0).getProjectedSpend()).isEqualByComparingTo("310.00");
    }

    @Test
    void ignoresIncomeAndOtherCategoriesWhenSummingSpend() {
        YearMonth month = YearMonth.of(2026, 3);
        Budget foodBudget = budget("Food", "100.00", "EUR");
        Expense food = expense("Food", "20.00", LocalDateTime.of(2026, 3, 2, 0, 0));
        Expense travel = expense("Travel", "999.00", LocalDateTime.of(2026, 3, 2, 0, 0));
        Expense income = expense("Food", "500.00", LocalDateTime.of(2026, 3, 2, 0, 0));
        income.setType(TransactionType.INCOME);
        LocalDate today = LocalDate.of(2026, 3, 20);

        List<BudgetAlertDTO> alerts = service.computeAlerts(
                month, List.of(foodBudget), List.of(food, travel, income), today);

        assertThat(alerts.get(0).getSpentAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void treatsDateAfterMonthEndAsFullyElapsedPeriod() {
        YearMonth month = YearMonth.of(2026, 1);
        Budget foodBudget = budget("Food", "100.00", "EUR");
        Expense spent = expense("Food", "50.00", LocalDateTime.of(2026, 1, 10, 0, 0));
        LocalDate today = LocalDate.of(2026, 3, 1);

        List<BudgetAlertDTO> alerts = service.computeAlerts(month, List.of(foodBudget), List.of(spent), today);

        assertThat(alerts.get(0).getPercentPeriodElapsed()).isEqualTo(1.0);
    }

    @Test
    void treatsDateBeforeMonthStartAsNoElapsedTime() {
        YearMonth month = YearMonth.of(2026, 5);
        Budget foodBudget = budget("Food", "100.00", "EUR");
        LocalDate today = LocalDate.of(2026, 3, 1);

        List<BudgetAlertDTO> alerts = service.computeAlerts(month, List.of(foodBudget), List.of(), today);

        assertThat(alerts.get(0).getPercentPeriodElapsed()).isEqualTo(0.0);
        assertThat(alerts.get(0).isOverPace()).isFalse();
    }

    @Test
    void flagsOverBudgetWhenLimitIsZeroAndAnySpendOccurs() {
        YearMonth month = YearMonth.of(2026, 3);
        Budget zeroBudget = budget("Alcohol", "0", "EUR");
        Expense spent = expense("Alcohol", "5.00", LocalDateTime.of(2026, 3, 10, 12, 0));
        LocalDate today = LocalDate.of(2026, 3, 15);

        List<BudgetAlertDTO> alerts = service.computeAlerts(month, List.of(zeroBudget), List.of(spent), today);

        BudgetAlertDTO alert = alerts.get(0);
        assertThat(alert.isOverBudget()).isTrue();
        assertThat(alert.isAlert()).isTrue();
        assertThat(alert.getMessage()).contains("exceeded");
        assertThat(alert.getMessage()).doesNotContain("Infinity");
    }

    @Test
    void defaultsCurrencyWhenBudgetHasNone() {
        YearMonth month = YearMonth.of(2026, 3);
        Budget foodBudget = budget("Food", "100.00", null);
        LocalDate today = LocalDate.of(2026, 3, 10);

        List<BudgetAlertDTO> alerts = service.computeAlerts(month, List.of(foodBudget), List.of(), today);

        assertThat(alerts.get(0).getCurrency()).isEqualTo("EUR");
    }
}
