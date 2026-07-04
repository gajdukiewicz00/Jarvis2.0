package org.jarvis.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
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

    /**
     * B2 — memory privacy enforcement. When false, results marked
     * {@code local_only} / {@code sensitive} are excluded (e.g. when the active
     * LLM provider is a remote API). Default true (local provider = include
     * everything), preserving prior behaviour.
     */
    @Builder.Default
    private boolean includeLocalOnly = true;

    @Builder.Default
    private boolean includeSensitive = true;
}


