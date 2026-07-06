package org.jarvis.nlp.model;

import java.util.List;
import java.util.Map;

/**
 * Enhanced NLU result with confidence scoring and clarification support.
 * Provides structured intent recognition with uncertainty handling.
 *
 * @param candidates top-K alternative intents to offer when {@code needsClarification}
 *                   is true (empty when the primary classification was confident enough
 *                   to execute directly)
 */
public record EnhancedNlpResult(
        String intent,
        Map<String, String> entities,
        double confidence, // 0.0 to 1.0
        boolean needsClarification,
        String clarificationQuestion,
        String originalText,
        List<IntentCandidate> candidates) {

    /**
     * High confidence threshold - execute directly
     */
    public static final double HIGH_CONFIDENCE = 0.8;

    /**
     * Low confidence threshold - request clarification
     */
    public static final double LOW_CONFIDENCE = 0.5;

    public EnhancedNlpResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /**
     * Convenience constructor for callers that don't need clarification candidates
     * (e.g. any confident, direct-execution result).
     */
    public EnhancedNlpResult(
            String intent,
            Map<String, String> entities,
            double confidence,
            boolean needsClarification,
            String clarificationQuestion,
            String originalText) {
        this(intent, entities, confidence, needsClarification, clarificationQuestion, originalText, List.of());
    }

    /**
     * Check if confidence is high enough for direct execution
     */
    public boolean isHighConfidence() {
        return confidence >= HIGH_CONFIDENCE;
    }

    /**
     * Check if confidence is too low (needs clarification)
     */
    public boolean isLowConfidence() {
        return confidence < LOW_CONFIDENCE;
    }

    /**
     * Check if confidence is medium (execute with warning)
     */
    public boolean isMediumConfidence() {
        return confidence >= LOW_CONFIDENCE && confidence < HIGH_CONFIDENCE;
    }
}
