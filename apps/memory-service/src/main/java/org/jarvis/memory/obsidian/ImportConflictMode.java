package org.jarvis.memory.obsidian;

import java.util.Locale;

/**
 * Roadmap P1 #9 — how {@link MemoryExportService#importNotesWithConflictResolution}
 * resolves an incoming import entry that conflicts with an already-existing
 * ACTIVE note (matched by {@code memoryId} first, then by content-hash).
 * Mirrors the {@link MemoryScope#fromString} lenient-parsing style so the
 * REST layer can accept case-insensitive, hyphen-or-underscore query params
 * (e.g. {@code ?mode=keep-both}).
 */
public enum ImportConflictMode {
    /** Leave the existing note untouched; the incoming entry is dropped. */
    SKIP,
    /** Replace the existing note's fields with the incoming entry's. */
    OVERWRITE,
    /** Keep the existing note as-is and additionally create a new, separate note. */
    KEEP_BOTH;

    public static ImportConflictMode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return SKIP;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return ImportConflictMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return SKIP;
        }
    }
}
