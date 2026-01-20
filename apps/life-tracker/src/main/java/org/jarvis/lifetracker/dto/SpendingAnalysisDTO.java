package org.jarvis.lifetracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SpendingAnalysisDTO {
    private String from;
    private String to;
    private String groupBy;
    private List<SpendingBucketDTO> buckets;
}
