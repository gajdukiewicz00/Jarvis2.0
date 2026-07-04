package org.jarvis.memory.obsidian;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 9 — owns the SPEC-1 "Jarvis, forget this" flow.
 *
 * <p>Three layers wiped, in order:</p>
 * <ol>
 *   <li>Obsidian — write a tombstone under
 *       {@code 06_System/deleted-memory-log/{YYYY-MM-DD}/{memory_id}.md},
 *       then remove the original active note. The tombstone carries
 *       only metadata, not the body.</li>
 *   <li>pgvector — null the embedding column.</li>
 *   <li>Postgres — soft-delete the row (status=DELETED, body cleared,
 *       summary cleared, deleted_at set). Hard-delete is left as a
 *       Phase 12 retention job to keep the {@code memory_id} present
 *       for audit foreign keys.</li>
 * </ol>
 *
 * <p>An audit event is emitted with category + memory_id only — no
 * sensitive content travels into the audit topic.</p>
 */
@Slf4j
@Service
public class MemoryForgetService {

    private final MemoryNoteRepository repository;
    private final ObsidianVaultWriter vaultWriter;
    private final ObjectProvider<AuditPublisher> auditProvider;

    public MemoryForgetService(MemoryNoteRepository repository,
                               ObsidianVaultWriter vaultWriter,
                               ObjectProvider<AuditPublisher> auditProvider) {
        this.repository = repository;
        this.vaultWriter = vaultWriter;
        this.auditProvider = auditProvider;
    }

    public record ForgetResult(boolean removed, String tombstonePath, String reason) {}

    @Transactional
    public ForgetResult forget(String memoryId, String actor, String reason) {
        Optional<MemoryNoteEntity> opt = repository.findById(memoryId);
        if (opt.isEmpty()) {
            return new ForgetResult(false, null, "not-found");
        }
        MemoryNoteEntity note = opt.get();
        if ("DELETED".equals(note.getStatus())) {
            return new ForgetResult(true, note.getVaultRelativePath(), "already-deleted");
        }

        // 1. Obsidian — tombstone first, then remove original. If tombstone
        //    write fails we still proceed to wipe Postgres + pgvector
        //    because the operator's intent is clear; the tombstone is best-effort.
        String tombstonePath = vaultWriter.writeTombstone(note,
                reason == null ? "Jarvis, forget this" : reason,
                actor == null ? "owner" : actor);
        if (note.getVaultRelativePath() != null) {
            vaultWriter.removeIfPresent(note.getVaultRelativePath());
        }

        // 2. pgvector — null the embedding column.
        note.setEmbedding(null);

        // 3. Postgres soft-delete: zero out content, mark DELETED.
        note.setStatus("DELETED");
        note.setBody(null);
        note.setSummary(null);
        note.setVaultRelativePath(tombstonePath);   // point to tombstone for traceability
        note.setDeletedAt(Instant.now());
        note.setUpdatedAt(Instant.now());
        if (note.getFrontmatter() != null) {
            note.getFrontmatter().put("status", "deleted");
            note.getFrontmatter().put("deleted_at", note.getDeletedAt().toString());
        }
        repository.save(note);

        // 4. Audit — metadata only; no sensitive content.
        Map<String, Object> payload = new HashMap<>();
        payload.put("category", note.getCategory());
        payload.put("privacy", note.getPrivacy());
        payload.put("tombstonePath", tombstonePath == null ? "" : tombstonePath);
        payload.put("actor", actor == null ? "" : actor);
        payload.put("reason", reason == null ? "" : reason);
        emitAudit(note.getMemoryId(), payload);

        log.info("[{}] memory FORGOTTEN by {} reason='{}' tombstone={}",
                note.getMemoryId(), actor, reason, tombstonePath);
        return new ForgetResult(true, tombstonePath, "deleted");
    }

    private void emitAudit(String memoryId, Map<String, Object> payload) {
        AuditPublisher publisher = auditProvider.getIfAvailable();
        if (publisher == null) return;
        publisher.audit(AuditEventType.MEMORY_DELETED, null, null, null,
                memoryId, payload);
    }
}
