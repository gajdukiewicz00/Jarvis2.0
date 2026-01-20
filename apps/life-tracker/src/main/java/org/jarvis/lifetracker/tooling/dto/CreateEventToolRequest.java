package org.jarvis.lifetracker.tooling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateEventToolRequest extends StrictToolRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 1000)
    private String description;

    @NotNull
    private LocalDateTime startTime;

    @NotNull
    private LocalDateTime endTime;

    private Boolean allDay;

    @Size(max = 255)
    private String location;

    @Size(max = 255)
    private String recurrenceRule;

    private LocalDateTime recurrenceUntil;

    @Size(max = 64)
    private String timezone;
}
