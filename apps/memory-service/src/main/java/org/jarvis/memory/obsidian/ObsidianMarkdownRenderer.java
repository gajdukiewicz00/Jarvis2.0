package org.jarvis.memory.obsidian;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 9 — renders a {@link MemoryNoteEntity} into Markdown with
 * stable YAML frontmatter.
 *
 * <p>Frontmatter contract per SPEC-1 § "Obsidian Memory Model":</p>
 * <pre>
 *   type, memory_id, source, created_at, updated_at, tags,
 *   confidence, linked_entities, privacy, status
 * </pre>
 *
 * <p>Order of keys is preserved (LinkedHashMap) so generated files diff
 * cleanly across re-renders. Strings are minimally escaped — newlines
 * inside summary/body are preserved in the body section (not in
 * frontmatter, which is single-line per key).</p>
 */
@Component
public class ObsidianMarkdownRenderer {

    public String render(MemoryNoteEntity note) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("---\n");
        for (Map.Entry<String, String> kv : frontmatterLines(note).entrySet()) {
            sb.append(kv.getKey()).append(": ").append(kv.getValue()).append('\n');
        }
        sb.append("---\n\n");

        if (note.getTitle() != null && !note.getTitle().isBlank()) {
            sb.append("# ").append(note.getTitle().trim()).append("\n\n");
        }

        if (note.getSummary() != null && !note.getSummary().isBlank()) {
            sb.append("## Summary\n\n").append(note.getSummary().trim()).append("\n\n");
        }

        if (note.getBody() != null && !note.getBody().isBlank()) {
            sb.append(note.getBody().trim()).append('\n');
        }
        return sb.toString();
    }

    /** Visible for tests: the YAML frontmatter as ordered key→value. */
    public LinkedHashMap<String, String> frontmatterLines(MemoryNoteEntity note) {
        LinkedHashMap<String, String> fm = new LinkedHashMap<>();
        fm.put("type", "memory");
        fm.put("memory_id", quote(note.getMemoryId()));
        fm.put("source", quote(note.getSource() == null ? "jarvis" : note.getSource()));
        fm.put("created_at", quote(formatInstant(note.getCreatedAt())));
        fm.put("updated_at", quote(formatInstant(note.getUpdatedAt())));
        fm.put("category", quote(note.getCategory()));
        fm.put("tags", renderList(note.tagList()));
        fm.put("confidence", note.getConfidence() == null ? "null" : note.getConfidence().toPlainString());
        fm.put("linked_entities", renderList(note.linkedEntityList()));
        fm.put("privacy", quote(note.getPrivacy() == null ? "local-only" : note.getPrivacy()));
        fm.put("status", quote(note.getStatus() == null ? "active" : note.getStatus().toLowerCase()));
        return fm;
    }

    /**
     * Renders the deletion-log tombstone — same frontmatter shape but
     * with {@code status: deleted} and the body wiped (only metadata
     * remains so audit + the user know "something existed here").
     */
    public String renderTombstone(MemoryNoteEntity note, String reason, String actor) {
        MemoryNoteEntity copy = note.toBuilder()
                .status("DELETED")
                .body(null)
                .summary(null)
                .build();
        StringBuilder sb = new StringBuilder(512);
        sb.append("---\n");
        for (Map.Entry<String, String> kv : frontmatterLines(copy).entrySet()) {
            sb.append(kv.getKey()).append(": ").append(kv.getValue()).append('\n');
        }
        sb.append("deleted_at: ").append(quote(formatInstant(Instant.now()))).append('\n');
        sb.append("deleted_by: ").append(quote(actor == null ? "unknown" : actor)).append('\n');
        sb.append("delete_reason: ").append(quote(reason == null ? "" : reason)).append('\n');
        sb.append("---\n\n");
        sb.append("> Memory was forgotten by Jarvis at the user's request.\n");
        sb.append("> Original content has been removed from Postgres, the\n");
        sb.append("> pgvector index, and the active Obsidian note. Only this\n");
        sb.append("> tombstone remains so the user can verify the action took place.\n");
        return sb.toString();
    }

    private String renderList(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(quote(values.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    private String quote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String formatInstant(Instant instant) {
        return instant == null ? "" : instant.toString();
    }
}
