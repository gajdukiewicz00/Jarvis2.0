package org.jarvis.planner.tooling.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.jarvis.planner.model.TaskPriority;
import org.jarvis.planner.model.TaskStatus;

import java.time.Instant;
import java.util.List;

@Data
public class UpdateTodoRequest extends StrictToolRequest {

    @NotNull
    private Long id;

    @Size(max = 500)
    private String title;

    @Size(max = 2000)
    private String description;

    private Instant dueDate;

    private TaskPriority priority;

    private TaskStatus status;

    private List<String> tags;

    @AssertTrue(message = "At least one field must be provided")
    public boolean isAnyFieldProvided() {
        return title != null
                || description != null
                || dueDate != null
                || priority != null
                || status != null
                || (tags != null && !tags.isEmpty());
    }
}
