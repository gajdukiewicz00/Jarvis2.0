package org.jarvis.lifetracker.tooling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SummarizeMonthToolRequest extends StrictToolRequest {

    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "month must be in YYYY-MM format")
    private String month;
}
