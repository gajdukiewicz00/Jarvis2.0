package org.jarvis.planner.tooling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.jarvis.planner.model.TaskPriority;

import java.time.Instant;
import java.util.List;

@Data
public class CreateTodoRequest extends StrictToolRequest {

    @NotBlank
    @Size(max = 500)
    private String title;

    @Size(max = 2000)
    private String description;

    private Instant dueDate;

    private TaskPriority priority;

    private List<String> tags;
}
