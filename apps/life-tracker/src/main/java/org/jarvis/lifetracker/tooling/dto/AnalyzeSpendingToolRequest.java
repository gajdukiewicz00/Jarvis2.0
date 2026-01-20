package org.jarvis.lifetracker.tooling.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AnalyzeSpendingToolRequest extends StrictToolRequest {

    @NotNull
    private LocalDateTime from;

    @NotNull
    private LocalDateTime to;

    @Pattern(regexp = "^(CATEGORY|MERCHANT)$", message = "groupBy must be CATEGORY or MERCHANT")
    private String groupBy;
}
