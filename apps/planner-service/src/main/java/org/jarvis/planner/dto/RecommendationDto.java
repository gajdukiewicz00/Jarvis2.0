package org.jarvis.planner.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.planner.model.RecommendationStatus;
import org.jarvis.planner.model.RecommendationType;
import org.jarvis.planner.model.TaskPriority;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationDto {
    private Long id;
    private RecommendationType type;
    private String title;
    private String message;
    private TaskPriority priority;
    private RecommendationStatus status;
    
    public RecommendationDto(RecommendationType type, String message, TaskPriority priority) {
        this.type = type;
        this.message = message;
        this.priority = priority;
    }
}
