package org.jarvis.lifetracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Pace alert for a single category budget within a month: compares how much of the budget
 * has been consumed against how much of the period has elapsed, so the user can be warned
 * they are "spending faster than budget" before the limit is actually breached.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetAlertDTO {
    private String category;
    private BigDecimal limitAmount;
    private BigDecimal spentAmount;
    private BigDecimal projectedSpend;
    private String currency;
    private boolean alert;
    private boolean overPace;
    private boolean overBudget;
    private double percentPeriodElapsed;
    private double percentBudgetUsed;
    private String message;
}
