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
 *   <li>{@code GET    /api/v1/memory/notes?category=&scope=&limit=} — list, optionally by category/scope</li>
 *   <li>{@code GET    /api/v1/memory/notes/{memoryId}}            — fetch one</li>
 *   <li>{@code GET    /api/v1/memory/notes/pending?scope=&limit=} — review/pending queue (low/no confidence)</li>
 *   <li>{@code GET    /api/v1/memory/notes/export?scope=}         — bulk export (plaintext)</li>
 *   <li>{@code GET    /api/v1/memory/notes/export/encrypted?scope=} — bulk export (AES-256/GCM, flagged)</li>
 *   <li>{@code GET    /api/v1/memory/notes/export/encrypted-or-plain?scope=} — encrypted when a key is
 *       configured, else the plaintext takeout with an explicit {@code encrypted:false} flag</li>
 *   <li>{@code POST   /api/v1/memory/notes/import}                — bulk import (plaintext)</li>
 *   <li>{@code POST   /api/v1/memory/notes/import/encrypted}      — bulk import (AES-256/GCM, flagged)</li>
 *   <li>{@code POST   /api/v1/memory/notes/import/resolve?mode=}  — bulk import (plaintext) with
 *       explicit skip/overwrite/keep-both conflict resolution</li>
 *   <li>{@code POST   /api/v1/memory/notes/import/encrypted/resolve?mode=} — encrypted variant of the above</li>
 *   <li>{@code GET    /api/v1/memory/notes/{memoryId}/why}        — "why does Jarvis remember this?"</li>
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
    private final MemoryExportService exportService;

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
                                       @RequestParam(required = false) MemoryScope scope,
                                       @RequestParam(defaultValue = "50") int limit) {
        return scope == null ? noteService.list(category, limit) : noteService.list(category, scope, limit);
    }

    /**
     * Roadmap P1 #9 — "memory review / pending" queue: ACTIVE notes Jarvis
     * wrote down with missing or low confidence, awaiting owner review.
     */
    @GetMapping("/pending")
    public List<MemoryNoteEntity> pending(@RequestParam(required = false) MemoryScope scope,
                                          @RequestParam(defaultValue = "50") int limit) {
        return noteService.pendingReview(scope, limit);
    }

    /** Edit an existing note (only provided fields change). */
    @PutMapping("/{memoryId}")
    public ResponseEntity<MemoryNoteEntity> update(@PathVariable String memoryId,
                                                   @RequestBody MemoryNoteRequest body) {
        MemoryNoteEntity updated = noteService.update(memoryId, body);
        return updated == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(updated);
    }

    /** Export all active notes, optionally scoped (data ownership / takeout). */
    @GetMapping("/export")
    public List<MemoryNoteEntity> export(@RequestParam(required = false) MemoryScope scope) {
        return noteService.exportAll(scope);
    }

    /**
     * Roadmap P1 #9 — AES-256/GCM encrypted export. Flagged: 400s unless
     * {@code jarvis.memory.export.encryption-key-base64} is configured.
     */
    @GetMapping("/export/encrypted")
    public MemoryExportService.ExportEnvelope exportEncrypted(@RequestParam(required = false) MemoryScope scope) {
        return exportService.exportEncrypted(scope);
    }

    /**
     * Roadmap P1 #9 — like {@link #exportEncrypted}, but falls back to the
     * plaintext takeout (flagged via {@code encrypted:false}) instead of a
     * 400 when no encryption key is configured.
     */
    @GetMapping("/export/encrypted-or-plain")
    public MemoryExportService.ExportPayload exportEncryptedOrPlain(
            @RequestParam(required = false) MemoryScope scope) {
        return exportService.exportPreferEncrypted(scope);
    }

    /** Roadmap P1 #9 — bulk import (plaintext); reuses the single-note write pipeline per entry. */
    @PostMapping("/import")
    public MemoryExportService.ImportSummary importNotes(@RequestBody List<MemoryNoteRequest> notes) {
        return exportService.importNotes(notes);
    }

    /** Roadmap P1 #9 — bulk import of an AES-256/GCM encrypted payload from {@code /export/encrypted}. */
    @PostMapping("/import/encrypted")
    public MemoryExportService.ImportSummary importEncrypted(
            @RequestBody MemoryExportService.ExportEnvelope envelope) {
        return exportService.importEncrypted(envelope);
    }

    /**
     * Roadmap P1 #9 — bulk import (plaintext) with explicit conflict
     * resolution ({@link ImportConflictMode}: skip / overwrite / keep-both),
     * matched by {@code memoryId} first and then by content-hash.
     */
    @PostMapping("/import/resolve")
    public MemoryExportService.ConflictImportSummary importWithConflictResolution(
            @RequestBody List<MemoryNoteRequest> notes,
            @RequestParam(defaultValue = "skip") String mode) {
        return exportService.importNotesWithConflictResolution(notes, ImportConflictMode.fromString(mode));
    }

    /** Encrypted-payload variant of {@link #importWithConflictResolution}. */
    @PostMapping("/import/encrypted/resolve")
    public MemoryExportService.ConflictImportSummary importEncryptedWithConflictResolution(
            @RequestBody MemoryExportService.ExportEnvelope envelope,
            @RequestParam(defaultValue = "skip") String mode) {
        return exportService.importEncryptedWithConflictResolution(envelope, ImportConflictMode.fromString(mode));
    }

    /**
     * Roadmap P1 #9 — "why does Jarvis remember this?": source, confidence,
     * scope, createdAt and a human-readable explanation for one note.
     */
    @GetMapping("/{memoryId}/why")
    public ResponseEntity<WhyRememberedResponse> why(@PathVariable String memoryId) {
        MemoryNoteEntity note = noteService.get(memoryId);
        return note == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(WhyRememberedResponse.from(note));
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
