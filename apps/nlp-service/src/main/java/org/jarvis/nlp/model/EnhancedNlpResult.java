package org.jarvis.nlp.model;

import java.util.Map;

/**
 * Enhanced NLU result with confidence scoring and clarification support.
 * Provides structured intent recognition with uncertainty handling.
 */
public record EnhancedNlpResult(
        String intent,
        Map<String, String> entities,
        double confidence, // 0.0 to 1.0
        boolean needsClarification,
        String clarificationQuestion,
        String originalText) {

    /**
     * High confidence threshold - execute directly
     */
    public static final double HIGH_CONFIDENCE = 0.8;

    /**
     * Low confidence threshold - request clarification
     */
    public static final double LOW_CONFIDENCE = 0.5;

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
