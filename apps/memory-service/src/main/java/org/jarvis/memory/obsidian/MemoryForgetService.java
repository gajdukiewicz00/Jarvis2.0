package org.jarvis.memory.obsidian;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Roadmap #11 — upper bound on how many notes a single "forget by query"
     * call will resolve and delete, so a broad/ambiguous voice utterance
     * cannot wipe an unbounded number of notes in one shot.
     */
    private static final int MAX_FORGET_BY_QUERY_MATCHES = 100;

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

    /** Result of {@link #forgetByQuery}: how many notes were forgotten, and their ids. */
    public record ForgetByQueryResult(int count, List<String> memoryIds) {}

    /**
     * Roadmap #11 — "forget by query": resolves a text query and/or scope filter
     * to zero or more ACTIVE notes and forgets each one through {@link #forget},
     * so the same tombstone + soft-delete + audit trail applies as a single-note
     * "forget this". Backs a voice "забудь это" ("forget this") command whose
     * utterance the caller (orchestrator / intent resolver) has already reduced
     * to a search query and/or scope — this method does no NLU of its own.
     *
     * @throws IllegalArgumentException if neither {@code query} nor {@code scope} is given
     */
    @Transactional
    public ForgetByQueryResult forgetByQuery(String query, MemoryScope scope, String actor, String reason) {
        List<MemoryNoteEntity> matches = resolveMatches(query, scope);
        List<String> forgottenIds = new ArrayList<>();
        for (MemoryNoteEntity note : matches) {
            ForgetResult result = forget(note.getMemoryId(), actor, reason);
            if (result.removed()) {
                forgottenIds.add(note.getMemoryId());
            }
        }
        return new ForgetByQueryResult(forgottenIds.size(), List.copyOf(forgottenIds));
    }

    /**
     * Text query (title/body keyword match, {@link MemoryNoteRepository#searchByText})
     * narrowed by scope when both are given; scope-only lookup
     * ({@link MemoryNoteRepository#searchByCategoryAndScope}) when there is no query.
     */
    private List<MemoryNoteEntity> resolveMatches(String query, MemoryScope scope) {
        boolean hasQuery = query != null && !query.isBlank();
        if (!hasQuery && scope == null) {
            throw new IllegalArgumentException("query or scope is required");
        }
        Pageable pageable = PageRequest.of(0, MAX_FORGET_BY_QUERY_MATCHES);
        if (!hasQuery) {
            return repository.searchByCategoryAndScope(null, scope.name(), "ACTIVE", pageable);
        }
        List<MemoryNoteEntity> textMatches = repository.searchByText(query, pageable);
        if (scope == null) {
            return textMatches;
        }
        String scopeStr = scope.name();
        List<MemoryNoteEntity> filtered = new ArrayList<>();
        for (MemoryNoteEntity note : textMatches) {
            if (scopeStr.equals(note.getScope())) {
                filtered.add(note);
            }
        }
        return filtered;
    }

    private void emitAudit(String memoryId, Map<String, Object> payload) {
        AuditPublisher publisher = auditProvider.getIfAvailable();
        if (publisher == null) return;
        publisher.audit(AuditEventType.MEMORY_DELETED, null, null, null,
                memoryId, payload);
    }
}
