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
}
