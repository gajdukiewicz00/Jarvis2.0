package org.jarvis.orchestrator.service;

import org.jarvis.orchestrator.dto.IntentExecutionResult;

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

    default IntentExecutionResult processTextDetailed(String text, String language, String correlationId) {
        return processTextDetailed(text, language, correlationId, null);
    }

    default IntentExecutionResult processTextDetailed(String text, String language, String correlationId, String userId) {
        return new IntentExecutionResult(processText(text, language, correlationId, userId), false, false, false, false, null);
    }

    default IntentExecutionResult executeIntentDetailed(
            String intent,
            Map<String, String> slots,
            String language,
            String correlationId,
            String originalText) {
        return executeIntentDetailed(intent, slots, language, correlationId, originalText, null);
    }

    default IntentExecutionResult executeIntentDetailed(
            String intent,
            Map<String, String> slots,
            String language,
            String correlationId,
            String originalText,
            String userId) {
        return new IntentExecutionResult(
                executeIntent(intent, slots, language, correlationId, originalText, userId),
                false,
                false,
                false,
                false,
                null);
    }
}
