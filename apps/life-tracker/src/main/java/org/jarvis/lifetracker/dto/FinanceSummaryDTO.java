package org.jarvis.lifetracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinanceSummaryDTO {
    private String month;
    private BigDecimal totalIncome;
    private BigDecimal totalExpense;
    private String currency;
    private Map<String, BigDecimal> byCategory;
}
