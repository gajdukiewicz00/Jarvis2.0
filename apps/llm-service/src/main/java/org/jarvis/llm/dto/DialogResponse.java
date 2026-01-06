package org.jarvis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.llm.model.Emotion;

import java.util.List;
import java.util.Map;

/**
 * Response from dialog mode interaction.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DialogResponse {
    
    /**
     * Session ID for conversation continuity
     */
    private String sessionId;
    
    /**
     * Generated reply text
     */
    private String reply;
    
    /**
     * Confidence score (0.0 - 1.0), if applicable
     */
    private Double confidence;
    
    /**
     * Suggested actions (optional, for command/scenario modes)
     */
    private List<String> actions;
    
    /**
     * Should the dialog continue? (false = return to command mode)
     */
    private boolean shouldContinue;
    
    /**
     * Current mode after this response
     */
    private String mode;
    
    /**
     * Emotion for TTS (optional)
     */
    private Emotion emotion;
    
    /**
     * Processing time in milliseconds
     */
    private long processingTimeMs;
    
    /**
     * Memory updates (optional, for orchestrator to store)
     */
    private Map<String, Object> memoryUpdate;
}

