package org.jarvis.lifetracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetUsageDTO {
    private String category;
    private BigDecimal limitAmount;
    private BigDecimal spentAmount;
    private String currency;
    private String status;
}
