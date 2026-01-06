package org.jarvis.memory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to search memory for relevant context
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchRequest {

    @NotBlank(message = "userId is required")
    private String userId;

    @NotBlank(message = "query is required")
    private String query;

    /**
     * Maximum number of chunks to return
     */
    @Min(1)
    @Max(20)
    @Builder.Default
    private int topK = 5;

    /**
     * Maximum tokens in combined result
     */
    @Min(100)
    @Max(2000)
    @Builder.Default
    private int maxTokens = 600;

    /**
     * Minimum similarity score (0-1)
     */
    @Min(0)
    @Max(1)
    @Builder.Default
    private double minSimilarity = 0.5;
}



