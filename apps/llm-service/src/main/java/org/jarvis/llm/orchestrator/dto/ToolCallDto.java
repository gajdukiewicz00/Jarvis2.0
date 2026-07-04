package org.jarvis.llm.orchestrator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallDto {
    private String name;
    private Map<String, Object> arguments;
    private Boolean requiresConfirmation;
    private String idempotencyKey;
    private Double confidence;
}
