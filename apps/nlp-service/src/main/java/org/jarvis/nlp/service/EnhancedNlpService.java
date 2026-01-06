package org.jarvis.nlp.service;

import org.jarvis.nlp.model.EnhancedNlpResult;
import org.jarvis.nlp.model.NlpResult;

/**
 * Enhanced NLP service interface with confidence scoring.
 */
public interface EnhancedNlpService {

    /**
     * Analyze text with confidence scoring and clarification support.
     * 
     * @param text         Input text to analyze
     * @param languageCode Language code (e.g., "ru", "en")
     * @return Enhanced NLP result with confidence and clarification info
     */
    EnhancedNlpResult analyzeWithConfidence(String text, String languageCode);

    /**
     * Legacy method for backward compatibility.
     * 
     * @deprecated Use analyzeWithConfidence instead
     */
    @Deprecated
    NlpResult infer(String text, String languageCode);
}
