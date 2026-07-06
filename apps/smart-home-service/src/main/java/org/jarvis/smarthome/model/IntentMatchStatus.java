package org.jarvis.smarthome.model;

/**
 * Outcome of matching a natural-language utterance — or the free-text device
 * reference extracted from it — against exactly one target (an action, or a
 * device in the registry).
 */
public enum IntentMatchStatus {
    /** Exactly one target was identified with usable confidence. */
    RESOLVED,
    /** More than one plausible target was identified; the caller must disambiguate. */
    AMBIGUOUS,
    /** No recognizable target was found. */
    UNKNOWN
}
