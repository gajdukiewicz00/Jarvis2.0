package org.jarvis.memory.obsidian;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Roadmap P1 #9 — ingest-time near-duplicate handling
 * ({@code jarvis.memory.dedup.*}).
 *
 * <p>{@link MemoryNoteService#write} hashes the normalized title+body of
 * every incoming note; when an ACTIVE note with the same hash already
 * exists, {@link #strategy} decides what happens.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jarvis.memory.dedup")
public class MemoryDedupProperties {

    /** Master switch. When false, every write creates a new row (legacy behaviour). */
    private boolean enabled = true;

    /** What to do when a same-content ACTIVE note already exists. */
    private DedupStrategy strategy = DedupStrategy.MERGE;

    public enum DedupStrategy {
        /** Fold the new write into the existing note (bump tags/confidence/timestamp). */
        MERGE,
        /** Reject the write with a 409 CONFLICT; caller must handle explicitly. */
        REJECT,
        /** Skip the dedup check entirely for this write. */
        NONE
    }
}
