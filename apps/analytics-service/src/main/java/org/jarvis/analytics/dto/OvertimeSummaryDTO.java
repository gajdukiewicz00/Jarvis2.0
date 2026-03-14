package org.jarvis.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OvertimeSummaryDTO {
    private Integer overtimeHours;
    private Double trackedWorkHours;
    private Integer baselineHours;
    private Integer trailingDays;
}
