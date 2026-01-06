package org.jarvis.analytics.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseSummaryDTO {
    private String period; // e.g. "2024-11" or "All"
    private String category; // e.g. "Food" or "All"

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal totalAmount; // Changed from Double

    private String currency;
    private int count;
}
