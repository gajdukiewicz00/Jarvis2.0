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
}
