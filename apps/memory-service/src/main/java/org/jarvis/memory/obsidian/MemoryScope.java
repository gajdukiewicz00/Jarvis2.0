package org.jarvis.memory.obsidian;

/**
 * Roadmap P1 #9 — typed memory scope.
 *
 * <p>Orthogonal to {@link MemoryCategory} (which drives Obsidian vault
 * directory layout): {@code scope} classifies a note by ownership /
 * lifecycle intent rather than by topic. Used for {@code GET
 * /api/v1/memory/notes?scope=} filtering and the review/pending queue.</p>
 */
public enum MemoryScope {
    /** Durable facts about the owner (name, preferences, relationships). */
    USER_PROFILE,
    /** Scoped to a specific project or piece of work. */
    PROJECT,
    /** Scoped to a single conversation/session; a natural TTL candidate. */
    SESSION,
    /** Financial data — budgets, accounts, transactions. */
    FINANCE,
    /** Health data — conditions, medications, appointments. */
    HEALTH,
    /** Short-lived scratch memory; a natural TTL candidate. */
    TEMPORARY;

    public static MemoryScope fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return USER_PROFILE;
        }
        try {
            return MemoryScope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return USER_PROFILE;
        }
    }
}
