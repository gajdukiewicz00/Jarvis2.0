package org.jarvis.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Chart-ready data structure for frontend visualization
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChartDataDTO {
    private String type; // "line", "bar", "pie"
    private List<String> labels; // X-axis labels (dates, categories)
    private List<Number> values; // Y-axis values (amounts, counts)
    private String title; // Chart title
    private String xAxisLabel; // X-axis label
    private String yAxisLabel; // Y-axis label
}
