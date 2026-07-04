package org.jarvis.llm.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelToolPlan {
    @JsonProperty("tool_calls")
    private List<ModelToolCall> toolCalls;

    private String explanation;

    private List<String> warnings;

    /** Model self-reported plan confidence in [0.0, 1.0]; null if the model omitted it. */
    private Double confidence;
}
