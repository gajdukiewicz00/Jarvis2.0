package org.jarvis.nlp.model;

/**
 * A single alternative intent surfaced when the top classification confidence
 * falls below the configured clarification threshold.
 *
 * <p>Used by {@link EnhancedNlpResult#candidates()} so the orchestrator can present
 * "did you mean..." style options instead of guessing on a low-confidence match.</p>
 *
 * @param intent     the candidate intent name (same naming as a normal classification result)
 * @param confidence the confidence score this candidate would have received (0.0 to 1.0)
 */
public record IntentCandidate(String intent, double confidence) {}
