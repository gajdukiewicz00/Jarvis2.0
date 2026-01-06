package org.jarvis.orchestrator.dto;

import java.util.Map;

public record LlmChatResponse(
        String reply,
        Map<String, Object> tokens,
        String model,
        int processingTimeMs,
        String emotion) {
}
