package org.jarvis.planner.tooling.dto;

import lombok.Data;
import org.jarvis.planner.model.TaskStatus;

import java.time.Instant;
import java.util.List;

@Data
public class ListTodosRequest extends StrictToolRequest {
    private TaskStatus status;
    private Instant from;
    private Instant to;
    private List<String> tags;
}
