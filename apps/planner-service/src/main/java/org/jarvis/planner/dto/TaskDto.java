package org.jarvis.planner.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.planner.model.TaskCategory;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.model.TaskSource;
import org.jarvis.planner.model.TaskStatus;

import java.time.Instant;
import java.util.List;

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
    private Instant dueDate;
    private List<String> tags;
    private TaskSource source;
    private String createdBy;
    private String updatedBy;
    private Integer estimatedDuration;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
}
