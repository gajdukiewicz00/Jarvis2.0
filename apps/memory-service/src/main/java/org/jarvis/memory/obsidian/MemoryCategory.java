package org.jarvis.memory.obsidian;

/**
 * Phase 9 — vault layout categories.
 *
 * <p>SPEC-1 § "Obsidian Vault Role" mandates the directory tree. Each
 * value here corresponds to one subdirectory under the vault root.
 * Reports / Decisions / SystemDeleted are top-level (not under {@code 03_Memory/}).</p>
 */
public enum MemoryCategory {
    PREFERENCES("03_Memory/Preferences"),
    HABITS("03_Memory/Habits"),
    PROJECTS("03_Memory/Projects"),
    HEALTH("03_Memory/Health"),
    FINANCE("03_Memory/Finance"),
    TIME("03_Memory/Time"),
    PEOPLE("03_Memory/People"),
    REPORTS("04_Reports"),
    DECISIONS("05_Decisions");

    private final String relativeDirectory;

    MemoryCategory(String relativeDirectory) {
        this.relativeDirectory = relativeDirectory;
    }

    public String relativeDirectory() {
        return relativeDirectory;
    }

    public static MemoryCategory fromString(String raw) {
        if (raw == null) return PROJECTS;
        try {
            return MemoryCategory.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return PROJECTS;
        }
    }
}
