package org.jarvis.planner.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.planner.model.TaskCategory;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.model.TaskStatus;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDto {
    private Long id;
    private String userId;
    private String title;
    private String description;
    private TaskCategory category;
    private TaskPriority priority;
    private TaskStatus status;
    private Instant deadline;
    private Integer estimatedDuration;
    private Instant createdAt;
    private Instant completedAt;
}
