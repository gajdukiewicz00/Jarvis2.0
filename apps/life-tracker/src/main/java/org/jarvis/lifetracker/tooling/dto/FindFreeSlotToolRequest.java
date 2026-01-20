package org.jarvis.lifetracker.tooling.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FindFreeSlotToolRequest extends StrictToolRequest {

    @NotNull
    private LocalDateTime from;

    @NotNull
    private LocalDateTime to;

    @Min(5)
    private int durationMinutes;

    private String workHoursStart;

    private String workHoursEnd;
}
