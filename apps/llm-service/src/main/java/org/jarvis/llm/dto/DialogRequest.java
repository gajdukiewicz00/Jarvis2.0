package org.jarvis.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request for dialog mode interaction.
 * Unlike simple /chat, this endpoint maintains session context and mode.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DialogRequest {
    
    /**
     * Session ID for conversation continuity
     */
    private String sessionId;
    
    /**
     * User ID for personalization
     */
    private String userId;
    
    /**
     * User's input text
     */
    private String input;
    
    /**
     * Language: "ru" or "en"
     */
    private String lang = "ru";
    
    /**
     * Interaction mode: "dialog", "command", "scenario"
     */
    private String mode = "dialog";
    
    /**
     * Optional context from other services (profile, planner, analytics)
     */
    private Map<String, Object> context;
}

