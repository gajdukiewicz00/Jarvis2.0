package org.jarvis.planner.tooling.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CompleteTodoRequest extends StrictToolRequest {

    @NotNull
    private Long id;
}
