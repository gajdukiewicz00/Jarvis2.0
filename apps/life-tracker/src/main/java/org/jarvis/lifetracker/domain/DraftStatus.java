package org.jarvis.lifetracker.domain;

/**
 * Lifecycle state of an {@link ExpenseDraft} sitting in the manual review inbox
 * (US-BANK-005: MEDIUM/LOW-confidence bank-notification parses land here instead of
 * being silently dropped).
 */
public enum DraftStatus {
    /** Awaiting a manual decision; shown in the review inbox listing. */
    DRAFT,
    /** Approved and turned into a real {@link Expense} (or matched an existing duplicate). */
    APPROVED,
    /** Discarded by the user; excluded from the review inbox listing. */
    REJECTED
}
