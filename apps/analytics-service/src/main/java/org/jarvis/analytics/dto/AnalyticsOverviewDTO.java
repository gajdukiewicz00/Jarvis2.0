package org.jarvis.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Analytics overview response with type-safe fields.
 * Replaces Map<String, Object> for better API stability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsOverviewDTO {

    // Expense metrics
    private BigDecimal totalExpenses;
    private Integer expenseCount;

    // Time tracking metrics
    private Long totalTimeTrackedSeconds;
    private Integer timeRecordCount;

    // Optional error fields
    private String expensesError;
    private String timeError;
}
