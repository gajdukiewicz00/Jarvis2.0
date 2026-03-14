package org.jarvis.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SleepSummaryDTO {
    private Double averageHours;
    private Integer daysSampled;
    private Integer trailingDays;
    private Double totalSleepHours;
}
