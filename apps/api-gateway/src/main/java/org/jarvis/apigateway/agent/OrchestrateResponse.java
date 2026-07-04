package org.jarvis.apigateway.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Response from llm-service POST /api/v1/llm/orchestrate — a validated tool plan (not yet executed). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrchestrateResponse {

    private String explanation;
    private List<PlannedToolCall> toolCalls;
    private List<String> warnings;
    private Double confidence;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlannedToolCall {
        private String name;
        private Map<String, Object> arguments;
        private Boolean requiresConfirmation;
        private String idempotencyKey;
        private Double confidence;
    }
}
