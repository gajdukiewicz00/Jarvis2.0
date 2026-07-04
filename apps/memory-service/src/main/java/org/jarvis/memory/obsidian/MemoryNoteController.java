package org.jarvis.memory.obsidian;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Phase 9 — REST surface for the Obsidian memory layer.
 *
 * <ul>
 *   <li>{@code POST   /api/v1/memory/notes}                       — write a new note</li>
 *   <li>{@code GET    /api/v1/memory/notes?category=&limit=}      — list by category</li>
 *   <li>{@code GET    /api/v1/memory/notes/{memoryId}}            — fetch one</li>
 *   <li>{@code DELETE /api/v1/memory/notes/{memoryId}?actor=&reason=} — forget</li>
 * </ul>
 *
 * <p>The DELETE path matches the SPEC's "Jarvis, forget this" command;
 * the orchestrator (Phase 5 confirmation) gates it as
 * {@code memory.delete-entry} (HIGH risk).</p>
 */
@RestController
@RequestMapping("/api/v1/memory/notes")
@RequiredArgsConstructor
public class MemoryNoteController {

    private final MemoryNoteService noteService;
    private final MemoryForgetService forgetService;

    @PostMapping
    public ResponseEntity<MemoryNoteEntity> write(@Valid @RequestBody MemoryNoteRequest body) {
        return ResponseEntity.ok(noteService.write(body));
    }

    @GetMapping("/{memoryId}")
    public ResponseEntity<MemoryNoteEntity> get(@PathVariable String memoryId) {
        MemoryNoteEntity note = noteService.get(memoryId);
        return note == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(note);
    }

    @GetMapping
    public List<MemoryNoteEntity> list(@RequestParam(required = false) MemoryCategory category,
                                       @RequestParam(defaultValue = "50") int limit) {
        return noteService.list(category, limit);
    }

    /** Edit an existing note (only provided fields change). */
    @PutMapping("/{memoryId}")
    public ResponseEntity<MemoryNoteEntity> update(@PathVariable String memoryId,
                                                   @RequestBody MemoryNoteRequest body) {
        MemoryNoteEntity updated = noteService.update(memoryId, body);
        return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
    }

    /** Export all active notes (data ownership / takeout). */
    @GetMapping("/export")
    public List<MemoryNoteEntity> export() {
        return noteService.exportAll();
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<MemoryForgetService.ForgetResult> forget(
            @PathVariable String memoryId,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String reason) {
        MemoryForgetService.ForgetResult result = forgetService.forget(memoryId, actor, reason);
        if (!result.removed()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }
}
