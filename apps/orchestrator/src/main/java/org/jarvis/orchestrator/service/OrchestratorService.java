package org.jarvis.orchestrator.service;

import java.util.Map;

public interface OrchestratorService {
    String processText(String text, String language, String correlationId);

    String executeIntent(String intent, Map<String, String> slots, String language, String correlationId,
            String originalText);
}
