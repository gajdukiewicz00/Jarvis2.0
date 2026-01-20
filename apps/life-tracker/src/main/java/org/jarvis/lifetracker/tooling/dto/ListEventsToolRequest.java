package org.jarvis.lifetracker.tooling.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ListEventsToolRequest extends StrictToolRequest {
    private LocalDateTime from;
    private LocalDateTime to;
}
