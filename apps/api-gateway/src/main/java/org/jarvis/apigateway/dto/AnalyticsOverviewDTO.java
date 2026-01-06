package org.jarvis.apigateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Analytics overview response DTO for api-gateway.
 * Mirrors analytics-service DTO for type-safe communication.
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
