package org.jarvis.lifetracker.tooling.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MoveEventToolRequest extends StrictToolRequest {

    @NotNull
    private Long eventId;

    @NotNull
    private LocalDateTime newStartTime;

    @NotNull
    private LocalDateTime newEndTime;
}
