package org.jarvis.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to generate or update session summary
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummarizeRequest {

    @NotBlank(message = "sessionId is required")
    private String sessionId;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String userId;

    /**
     * Force regeneration even if summary exists
     */
    @Builder.Default
    private boolean forceRegenerate = false;
}


