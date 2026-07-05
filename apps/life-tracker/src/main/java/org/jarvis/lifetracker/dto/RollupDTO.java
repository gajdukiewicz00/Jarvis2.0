package org.jarvis.lifetracker.dto;

import org.jarvis.lifetracker.domain.WellnessType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Weekly / monthly aggregate combining finance + wellness data for a user. */
public record RollupDTO(
        String period,          // "WEEK" | "MONTH"
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        String currency,
        Map<String, BigDecimal> expenseByCategory,
        Map<WellnessType, Double> wellnessAverages,
        Double habitCompletionRate,   // fraction 0..1 of habit-days completed; null if no habit entries
        int wellnessEntryCount,
        int transactionCount
) {
}
