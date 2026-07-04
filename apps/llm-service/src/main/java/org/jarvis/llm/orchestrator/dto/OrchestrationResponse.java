package org.jarvis.llm.orchestrator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationResponse {
    private String explanation;
    private List<ToolCallDto> toolCalls;
    private List<String> warnings;
    private String rawModelOutput;
    /** Aggregate plan confidence in [0.0, 1.0] as reported by the model; null if absent. */
    private Double confidence;
}
