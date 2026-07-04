package org.jarvis.memory.obsidian;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase 9 — incoming write request for a memory note.
 *
 * <p>Used both by the REST surface ({@code POST /api/v1/memory/notes})
 * and by internal callers (orchestrator after a "remember this" intent).
 * {@code memoryId} is optional — minted when blank.</p>
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MemoryNoteRequest {

    private String memoryId;
    private MemoryCategory category;
    private String title;
    private String summary;
    private String body;
    private String source;            // default "jarvis"
    private String privacy;           // default "local-only"
    private BigDecimal confidence;
    private List<String> tags;
    private List<String> linkedEntities;
}
