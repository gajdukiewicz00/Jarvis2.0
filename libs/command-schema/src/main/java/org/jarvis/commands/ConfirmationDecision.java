package org.jarvis.commands;

/**
 * Phase 5 — outcome of a confirmation round-trip.
 */
public enum ConfirmationDecision {
    /** Owner explicitly approved (UI click, voice "yes"). */
    APPROVED,
    /** Owner explicitly denied. */
    DENIED,
    /** No decision arrived before the deadline. Treated as a deny. */
    TIMEOUT,
    /** Demo / non-owner mode auto-blocked the action. */
    BLOCKED_DEMO_MODE,
    /** Speaker did not match the registered owner (Phase 6 owner voice check). */
    BLOCKED_NON_OWNER
}
