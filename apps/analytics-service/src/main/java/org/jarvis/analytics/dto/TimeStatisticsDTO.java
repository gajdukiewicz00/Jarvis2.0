package org.jarvis.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeStatisticsDTO {
    private String category;
    private Double totalDurationHours;
    private Integer activityCount;
    private Double averageDurationHours;
}
