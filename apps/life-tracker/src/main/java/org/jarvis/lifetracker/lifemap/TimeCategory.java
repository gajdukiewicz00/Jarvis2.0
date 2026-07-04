package org.jarvis.lifetracker.lifemap;

/**
 * Phase 11 — coarse classification of how the user spends time.
 *
 * <p>SPEC-1 § Phase 11 mandates exactly six buckets. The classifier
 * {@link TimeClassifier} maps a window title / app name / domain to
 * one of these. {@link #CUSTOM} is the catch-all for entries the user
 * explicitly tagged.</p>
 */
public enum TimeCategory {
    WORK,
    STUDY,
    REST,
    SPORT,
    SLEEP,
    CUSTOM
}
