package org.jarvis.orchestrator.service;

import java.util.Map;

public interface OrchestratorService {
    default String processText(String text, String language, String correlationId) {
        return processText(text, language, correlationId, null);
    }

    String processText(String text, String language, String correlationId, String userId);

    default String executeIntent(String intent, Map<String, String> slots, String language, String correlationId,
            String originalText) {
        return executeIntent(intent, slots, language, correlationId, originalText, null);
    }

    String executeIntent(String intent, Map<String, String> slots, String language, String correlationId,
            String originalText, String userId);
}
