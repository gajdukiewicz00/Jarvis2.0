package org.jarvis.llm.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrchestrationRequest {
    @NotBlank
    private String sessionId;

    @NotBlank
    private String userId;

    @NotBlank
    private String intent;

    private Map<String, Object> context;

    private Boolean includeMemory;

    private String locale;

    private Integer maxToolCalls;
}
