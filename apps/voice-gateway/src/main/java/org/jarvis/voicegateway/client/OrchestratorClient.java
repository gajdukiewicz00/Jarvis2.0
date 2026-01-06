package org.jarvis.voicegateway.client;

import java.util.Map;

public interface OrchestratorClient {
    
    /**
     * Send raw text command to orchestrator (legacy method).
     */
    void sendCommand(String text);
    
    /**
     * Send structured intent to orchestrator for execution.
     * 
     * @param action The intent action (e.g., "VOLUME_UP", "VOLUME_DOWN")
     * @param parameters Optional parameters for the action
     * @param language Language code (e.g., "ru", "en")
     * @param correlationId Correlation ID for tracing
     * @return Response text from orchestrator (for TTS)
     */
    String sendIntent(String action, Map<String, Object> parameters, String language, String correlationId);
    
    /**
     * Send structured intent to orchestrator for execution with original text.
     * 
     * @param action The intent action (e.g., "VOLUME_UP", "VOLUME_DOWN", "fallback")
     * @param parameters Optional parameters for the action
     * @param language Language code (e.g., "ru", "en")
     * @param correlationId Correlation ID for tracing
     * @param originalText Original user text (for LLM fallback)
     * @return Response text from orchestrator (for TTS)
     */
    default String sendIntent(String action, Map<String, Object> parameters, String language, 
                              String correlationId, String originalText) {
        // Default implementation: ignore originalText for backwards compatibility
        return sendIntent(action, parameters, language, correlationId);
    }
}
