package org.jarvis.memory.tooling.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class SearchMemoryToolRequest extends StrictToolRequest {

    @NotBlank
    private String query;

    @Positive
    private Integer topK;

    @Positive
    private Integer maxTokens;
}
