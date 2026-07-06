package org.jarvis.smarthome.model;

/**
 * Result of rule-based natural-language parsing of a smart-home utterance,
 * produced by {@code IntentParser}. This is parsing only — {@code deviceQuery}
 * is the free-text device reference extracted from the utterance, not yet
 * resolved against the device registry (see {@code SmartHomeIntentService}
 * for that step).
 */
public record ParsedIntent(
        IntentMatchStatus status,
        String action,
        String deviceQuery,
        String payload,
        double confidence,
        String message) {
}
