package org.jarvis.memory.obsidian;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * Roadmap P1 #9 — {@code GET /api/v1/memory/notes/{memoryId}/why} response:
 * the raw provenance fields plus a human-readable explanation, so a caller
 * (owner-facing UI, or Jarvis itself when asked "why do you remember this?")
 * doesn't have to reconstruct the sentence from {@link MemoryNoteEntity}
 * fields itself.
 */
public record WhyRememberedResponse(
        String memoryId,
        String source,
        BigDecimal confidence,
        String scope,
        Instant createdAt,
        String explanation) {

    public static WhyRememberedResponse from(MemoryNoteEntity note) {
        return new WhyRememberedResponse(
                note.getMemoryId(),
                note.getSource(),
                note.getConfidence(),
                note.getScope(),
                note.getCreatedAt(),
                buildExplanation(note));
    }

    private static String buildExplanation(MemoryNoteEntity note) {
        StringBuilder sb = new StringBuilder("Jarvis remembered this");
        String source = note.getSource();
        if (source != null && !source.isBlank()) {
            sb.append(" because it came from ").append(source);
        } else {
            sb.append(" from an unrecorded source");
        }
        if (note.getCreatedAt() != null) {
            sb.append(" on ").append(note.getCreatedAt());
        }
        String scope = note.getScope();
        if (scope != null && !scope.isBlank()) {
            sb.append(", scoped as ").append(scope.toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        if (note.getConfidence() != null) {
            sb.append(", with confidence ").append(note.getConfidence().toPlainString());
        } else {
            sb.append(", with no recorded confidence");
        }
        sb.append('.');
        return sb.toString();
    }
}
