package org.jarvis.llm.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ModelToolCall {
    private String name;
    private Map<String, Object> arguments;

    @JsonProperty("requires_confirmation")
    private Boolean requiresConfirmation;

    /** Optional per-call confidence in [0.0, 1.0]; null if omitted by the model. */
    private Double confidence;
}
