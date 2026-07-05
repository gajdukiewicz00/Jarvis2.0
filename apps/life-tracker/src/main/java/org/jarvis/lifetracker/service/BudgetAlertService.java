package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.Budget;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.BudgetAlertDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes "you're spending faster than budget" pace alerts by comparing how much of a
 * category budget has been consumed against how much of the month has elapsed.
 *
 * <p>Plain, stateless algorithm class (not a Spring bean) — {@link FinanceService} owns an
 * instance directly so it never has to be mocked out in service-layer tests; it is unit-tested
 * on its own in {@code BudgetAlertServiceTest}.
 */
public class BudgetAlertService {

    private static final String DEFAULT_CURRENCY = "EUR";

    public List<BudgetAlertDTO> computeAlerts(
            YearMonth month, List<Budget> budgets, List<Expense> expensesInMonth, LocalDate today) {
        List<BudgetAlertDTO> alerts = new ArrayList<>();
        for (Budget budget : budgets) {
            BigDecimal spent = expensesInMonth.stream()
                    .filter(expense -> expense.getType() == TransactionType.EXPENSE)
                    .filter(expense -> budget.getCategory().equalsIgnoreCase(expense.getCategory()))
                    .map(Expense::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            alerts.add(toAlert(month, budget, spent, today));
        }
        return alerts;
    }

    private BudgetAlertDTO toAlert(YearMonth month, Budget budget, BigDecimal spent, LocalDate today) {
        int totalDays = month.lengthOfMonth();
        int elapsedDays = elapsedDays(month, today, totalDays);

        BigDecimal limit = budget.getLimitAmount();
        double percentPeriodElapsed = totalDays == 0 ? 0 : elapsedDays / (double) totalDays;
        double percentBudgetUsed = percentBudgetUsed(limit, spent);
        BigDecimal projectedSpend = projectedSpend(spent, totalDays, elapsedDays);

        boolean overBudget = limit.signum() > 0 && spent.compareTo(limit) > 0;
        boolean overPace = !overBudget && elapsedDays > 0 && percentBudgetUsed > percentPeriodElapsed;
        boolean alert = overBudget || overPace;
        String currency = budget.getCurrency() != null ? budget.getCurrency() : DEFAULT_CURRENCY;

        return new BudgetAlertDTO(
                budget.getCategory(),
                limit,
                spent,
                projectedSpend,
                currency,
                alert,
                overPace,
                overBudget,
                round(percentPeriodElapsed),
                round(percentBudgetUsed),
                buildMessage(budget.getCategory(), overBudget, overPace, spent, limit, currency,
                        percentBudgetUsed, percentPeriodElapsed));
    }

    private int elapsedDays(YearMonth month, LocalDate today, int totalDays) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        if (today.isBefore(monthStart)) {
            return 0;
        }
        if (today.isAfter(monthEnd)) {
            return totalDays;
        }
        return (int) ChronoUnit.DAYS.between(monthStart, today) + 1;
    }

    private double percentBudgetUsed(BigDecimal limit, BigDecimal spent) {
        if (limit.signum() <= 0) {
            return spent.signum() > 0 ? Double.POSITIVE_INFINITY : 0.0;
        }
        return spent.divide(limit, 6, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal projectedSpend(BigDecimal spent, int totalDays, int elapsedDays) {
        if (elapsedDays <= 0) {
            return spent;
        }
        return spent.multiply(BigDecimal.valueOf(totalDays))
                .divide(BigDecimal.valueOf(elapsedDays), 2, RoundingMode.HALF_UP);
    }

    private String buildMessage(String category, boolean overBudget, boolean overPace, BigDecimal spent,
            BigDecimal limit, String currency, double percentBudgetUsed, double percentPeriodElapsed) {
        if (overBudget) {
            return String.format("%s budget exceeded: spent %s of %s %s.",
                    category, spent.toPlainString(), limit.toPlainString(), currency);
        }
        if (overPace) {
            return String.format("%s spending is ahead of budget pace: %.0f%% used after %.0f%% of the month.",
                    category, percentBudgetUsed * 100, percentPeriodElapsed * 100);
        }
        return String.format("%s spending is on track.", category);
    }

    private double round(double value) {
        if (Double.isInfinite(value) || Double.isNaN(value)) {
            return value;
        }
        return Math.round(value * 10000d) / 10000d;
    }
}
