package org.jarvis.memory.obsidian;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.jarvis.memory.exception.DuplicateMemoryException;
import org.jarvis.memory.service.MemoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Phase 9 — orchestrates the three-layer memory write:
 * Postgres → Obsidian Markdown → pgvector embedding → audit.
 *
 * <p>Postgres is committed first so an Obsidian / embedding-service
 * outage cannot leave Jarvis without a record of the user's action.
 * The vault write and embedding compute happen after the row is saved
 * and update the same row in-place.</p>
 */
@Slf4j
@Service
public class MemoryNoteService {

    private final MemoryNoteRepository repository;
    private final ObsidianVaultWriter vaultWriter;
    private final ObsidianMarkdownRenderer renderer;
    private final MemoryEmbeddingClient embeddingClient;
    private final ObjectProvider<AuditPublisher> auditProvider;

    @PersistenceContext
    private EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // Roadmap P1 #9 — optional field-injected config, same pattern as jdbcTemplate
    // above. Kept out of the constructor so existing test call sites (which build
    // this service with 5 positional args) are unaffected; falls back to
    // property-class defaults when not wired by Spring.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryDedupProperties dedupProperties;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryReviewProperties reviewProperties;

    // Roadmap #16 — same optional field-injection pattern as dedupProperties/reviewProperties
    // above, so the existing 5-positional-arg test call sites stay unaffected.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryMetrics metrics;

    private MemoryDedupProperties effectiveDedupProperties() {
        return dedupProperties != null ? dedupProperties : new MemoryDedupProperties();
    }

    private MemoryReviewProperties effectiveReviewProperties() {
        return reviewProperties != null ? reviewProperties : new MemoryReviewProperties();
    }

    private MemoryMetrics effectiveMetrics() {
        return metrics != null ? metrics : new MemoryMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    public MemoryNoteService(MemoryNoteRepository repository,
                             ObsidianVaultWriter vaultWriter,
                             ObsidianMarkdownRenderer renderer,
                             MemoryEmbeddingClient embeddingClient,
                             ObjectProvider<AuditPublisher> auditProvider) {
        this.repository = repository;
        this.vaultWriter = vaultWriter;
        this.renderer = renderer;
        this.embeddingClient = embeddingClient;
        this.auditProvider = auditProvider;
    }

    @Transactional
    public MemoryNoteEntity write(MemoryNoteRequest request) {
        return writeWithOutcome(request).note();
    }

    /**
     * Result of {@link #writeWithOutcome}: the note that now represents this
     * write, plus whether it was folded into an already-existing near-duplicate
     * (see {@link MemoryDedupProperties}) rather than created fresh.
     */
    public record WriteOutcome(MemoryNoteEntity note, boolean merged) {}

    /**
     * Same write path as {@link #write}, but also reports whether the request
     * was merged into an existing near-duplicate note. Used by bulk import
     * ({@link MemoryExportService}) to report created-vs-merged counts.
     */
    @Transactional
    public WriteOutcome writeWithOutcome(MemoryNoteRequest request) {
        return writeWithOutcome(request, false);
    }

    /**
     * Roadmap P1 #9 — same as {@link #writeWithOutcome(MemoryNoteRequest)}, but
     * lets a caller skip the ingest-time content-hash dedup check. Used by
     * {@link MemoryExportService}'s "keep both" import-conflict resolution,
     * which must guarantee a distinct new note even when its content is
     * identical to an already-existing one (the exact case ordinary dedup
     * exists to collapse).
     */
    @Transactional
    public WriteOutcome writeWithOutcome(MemoryNoteRequest request, boolean bypassDedup) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        // Upsert for Obsidian-indexed notes: the indexer sends a stable
        // "obsidian:<vault-path>" source. Reindexing the same file must UPDATE the
        // existing row (and re-embed) instead of creating a duplicate every run.
        // Explicit manual notes (source "jarvis"/null) are unaffected.
        String src = request.getSource();
        if ((request.getMemoryId() == null || request.getMemoryId().isBlank())
                && src != null && src.startsWith("obsidian:")) {
            MemoryNoteEntity existing =
                    repository.findFirstBySourceOrderByCreatedAtDesc(src).orElse(null);
            if (existing != null) {
                return new WriteOutcome(update(existing.getMemoryId(), request), false);
            }
        }

        // Roadmap P1 #9 — ingest-time dedup: a same-content ACTIVE note already
        // present is either merged into (default) or rejected outright, instead
        // of silently accumulating copies of the same fact.
        String contentHash = computeContentHash(request.getTitle(), request.getBody());
        MemoryDedupProperties dedup = effectiveDedupProperties();
        if (!bypassDedup && dedup.isEnabled() && dedup.getStrategy() != MemoryDedupProperties.DedupStrategy.NONE) {
            MemoryNoteEntity duplicate = repository
                    .findFirstByContentHashAndStatusOrderByCreatedAtDesc(contentHash, "ACTIVE")
                    .orElse(null);
            if (duplicate != null) {
                if (dedup.getStrategy() == MemoryDedupProperties.DedupStrategy.REJECT) {
                    effectiveMetrics().dedupRejected();
                    throw new DuplicateMemoryException(duplicate.getMemoryId());
                }
                return new WriteOutcome(mergeDuplicate(duplicate, request), true);
            }
        }

        MemoryCategory category = request.getCategory() == null
                ? MemoryCategory.PROJECTS : request.getCategory();
        MemoryScope scope = request.getScope() == null ? MemoryScope.USER_PROFILE : request.getScope();
        Instant now = Instant.now();
        String memoryId = (request.getMemoryId() == null || request.getMemoryId().isBlank())
                ? MemoryNoteEntity.newMemoryId() : request.getMemoryId();
        // Resolved once so the persisted row and the Obsidian frontmatter never
        // disagree about a finance/health note's privacy.
        String privacy = resolvePrivacy(scope, request.getPrivacy());

        MemoryNoteEntity note = MemoryNoteEntity.builder()
                .memoryId(memoryId)
                .category(category.name())
                .scope(scope.name())
                .title(request.getTitle().trim())
                .summary(request.getSummary())
                .body(request.getBody())
                .source(request.getSource() == null ? "jarvis" : request.getSource())
                .privacy(privacy)
                .status("ACTIVE")
                .confidence(request.getConfidence())
                .contentHash(contentHash)
                .expiresAt(resolveExpiresAt(request, now))
                .tags(request.getTags() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(request.getTags()))
                .linkedEntities(request.getLinkedEntities() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(request.getLinkedEntities()))
                .createdAt(now)
                .updatedAt(now)
                .frontmatter(initialFrontmatter(memoryId, category, now,
                        request.getSource(), privacy, request.getTags(),
                        request.getLinkedEntities(), request.getConfidence()))
                .build();

        note = repository.save(note);

        String relativePath = vaultWriter.write(note);
        if (relativePath != null) {
            note.setVaultRelativePath(relativePath);
            note.getFrontmatter().put("vault_relative_path", relativePath);
        }
        note.setEmbedding(embeddingClient.embed(buildEmbeddingInput(note)));
        note.setUpdatedAt(Instant.now());
        note = repository.save(note);

        emitAudit(AuditEventType.MEMORY_WRITTEN, note,
                Map.of("category", note.getCategory(),
                       "vaultPath", note.getVaultRelativePath() == null ? "" : note.getVaultRelativePath(),
                       "embedded", note.getEmbedding() != null));
        return new WriteOutcome(note, false);
    }

    /**
     * Folds an incoming write into an already-existing content-identical note:
     * unions tags/linked-entities, keeps the higher confidence, refreshes TTL
     * when the request specifies one, and bumps {@code updatedAt}. Does not
     * re-write the vault file or re-embed — the title/body are unchanged by
     * definition (that's what made it a dedup hit).
     */
    private MemoryNoteEntity mergeDuplicate(MemoryNoteEntity existing, MemoryNoteRequest request) {
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(existing.tagList());
            merged.addAll(request.getTags());
            existing.setTags(new java.util.ArrayList<>(merged));
        }
        if (request.getLinkedEntities() != null && !request.getLinkedEntities().isEmpty()) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(existing.linkedEntityList());
            merged.addAll(request.getLinkedEntities());
            existing.setLinkedEntities(new java.util.ArrayList<>(merged));
        }
        if (request.getConfidence() != null
                && (existing.getConfidence() == null
                    || request.getConfidence().compareTo(existing.getConfidence()) > 0)) {
            existing.setConfidence(request.getConfidence());
        }
        Instant now = Instant.now();
        Instant requestedExpiry = resolveExpiresAt(request, now);
        if (requestedExpiry != null) {
            existing.setExpiresAt(requestedExpiry);
        }
        existing.setUpdatedAt(now);
        if (existing.getFrontmatter() != null) {
            existing.getFrontmatter().put("dedup_merged_at", now.toString());
        }
        existing = repository.save(existing);
        emitAudit(AuditEventType.MEMORY_WRITTEN, existing,
                Map.of("category", existing.getCategory(), "merged", true));
        return existing;
    }

    /** Explicit {@code expiresAt} wins; otherwise derive from {@code ttlSeconds}; else no TTL. */
    private static Instant resolveExpiresAt(MemoryNoteRequest request, Instant now) {
        if (request.getExpiresAt() != null) {
            return request.getExpiresAt();
        }
        if (request.getTtlSeconds() != null && request.getTtlSeconds() > 0) {
            return now.plusSeconds(request.getTtlSeconds());
        }
        return null;
    }

    /**
     * Roadmap — finance/health notes are inherently sensitive: their privacy
     * is always forced to {@code local-only} regardless of what the caller
     * requested, so a downstream consumer can reliably keep them away from an
     * external LLM via {@link MemoryService#privacyAllowed} (see
     * {@link #searchUnified(String, int, boolean, boolean)}).
     */
    static boolean isSensitiveScope(MemoryScope scope) {
        return scope == MemoryScope.FINANCE || scope == MemoryScope.HEALTH;
    }

    private static String resolvePrivacy(MemoryScope scope, String requestedPrivacy) {
        if (isSensitiveScope(scope)) {
            return "local-only";
        }
        return requestedPrivacy == null ? "local-only" : requestedPrivacy;
    }

    /**
     * SHA-256 over normalized title+body — stable regardless of whitespace/case
     * drift. Package-private (not {@code private}) so {@link MemoryExportService}
     * can reuse it for import-time conflict detection instead of duplicating the
     * hashing scheme.
     */
    static String computeContentHash(String title, String body) {
        String normalized = normalize(title) + "" + normalize(body);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    public MemoryNoteEntity get(String memoryId) {
        return repository.findById(memoryId).orElse(null);
    }

    public List<MemoryNoteEntity> list(MemoryCategory category, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        String categoryStr = category == null ? null : category.name();
        return repository.search(categoryStr, "ACTIVE",
                org.springframework.data.domain.PageRequest.of(0, safeLimit));
    }

    /**
     * Roadmap P1 #9 — category+scope aware listing, backing
     * {@code GET /api/v1/memory/notes?category=&scope=}. Additive alongside
     * {@link #list(MemoryCategory, int)}.
     */
    public List<MemoryNoteEntity> list(MemoryCategory category, MemoryScope scope, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        String categoryStr = category == null ? null : category.name();
        String scopeStr = scope == null ? null : scope.name();
        return repository.searchByCategoryAndScope(categoryStr, scopeStr, "ACTIVE",
                org.springframework.data.domain.PageRequest.of(0, safeLimit));
    }

    /**
     * Roadmap P1 #9 — the "memory review / pending" queue: ACTIVE notes whose
     * confidence is missing or below {@code jarvis.memory.review.pending-confidence-threshold}.
     * Backs {@code GET /api/v1/memory/notes/pending}.
     */
    public List<MemoryNoteEntity> pendingReview(MemoryScope scope, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        String scopeStr = scope == null ? null : scope.name();
        return repository.findPendingReview(scopeStr, effectiveReviewProperties().getPendingConfidenceThreshold(),
                org.springframework.data.domain.PageRequest.of(0, safeLimit));
    }

    /** Result of {@link #searchUnified}: the notes plus the mode actually used. */
    public record NoteSearchResult(List<MemoryNoteEntity> notes, String mode) {
    }

    /**
     * Semantic-first note search: embed the query and rank notes by pgvector cosine
     * distance; fall back to keyword (title/body LIKE) if embeddings are unavailable
     * or return nothing. Lets Obsidian notes be found even without exact keywords.
     */
    public NoteSearchResult searchUnified(String query, int topK) {
        int k = Math.min(Math.max(topK, 1), 50);
        try {
            float[] qv = embeddingClient.embed(query);
            log.info("searchUnified diag: query='{}' qvLen={}", query, qv == null ? -1 : qv.length);
            if (qv != null && qv.length > 0) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < qv.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(qv[i]);
                }
                sb.append(']');
                // Plain JDBC via JdbcTemplate — the Hibernate native-query path throws
                // "No results were returned" for this pgvector query on this driver.
                // SELECT only non-vector columns (never read the embedding back).
                if (jdbcTemplate != null) {
                    // Roadmap #11 — pinned notes rank ahead of non-pinned ones even in
                    // semantic search; "pinned" need not be SELECTed to be ORDERed by.
                    String sql = "SELECT memory_id, title, category, body, vault_relative_path, created_at"
                            + " FROM memory_notes WHERE embedding IS NOT NULL"
                            + " AND (status IS NULL OR status <> 'deleted')"
                            + " AND (embedding <=> CAST(? AS vector)) <= ?"
                            + " ORDER BY pinned DESC, embedding <=> CAST(? AS vector) LIMIT " + k;
                    List<java.util.Map<String, Object>> rows =
                            jdbcTemplate.queryForList(sql, sb.toString(), 0.75, sb.toString());
                    if (!rows.isEmpty()) {
                        List<MemoryNoteEntity> notes = new java.util.ArrayList<>();
                        for (java.util.Map<String, Object> r : rows) {
                            MemoryNoteEntity n = new MemoryNoteEntity();
                            n.setMemoryId(str(r.get("memory_id")));
                            n.setTitle(str(r.get("title")));
                            n.setCategory(str(r.get("category")));
                            n.setBody(str(r.get("body")));
                            n.setVaultRelativePath(str(r.get("vault_relative_path")));
                            Object ts = r.get("created_at");
                            if (ts instanceof java.sql.Timestamp t) {
                                n.setCreatedAt(t.toInstant());
                            } else if (ts instanceof java.time.Instant inst) {
                                n.setCreatedAt(inst);
                            }
                            notes.add(n);
                        }
                        return new NoteSearchResult(notes, "semantic");
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("note semantic search failed, falling back to keyword: {}", e.getMessage());
        }
        return new NoteSearchResult(
                repository.searchByText(query, org.springframework.data.domain.PageRequest.of(0, k)),
                "keyword");
    }

    /**
     * Roadmap — privacy-aware variant of {@link #searchUnified(String, int)}.
     * Mirrors {@link MemoryService}'s B2 chunk-store filter: when the caller
     * cannot receive {@code local-only} / {@code sensitive} content (e.g. the
     * active provider is a remote/external LLM), matching notes flagged that
     * way are dropped from the result set. Finance/health-scoped notes are
     * always {@code local-only} (see {@link #resolvePrivacy}), so this is the
     * guard that keeps them from reaching an external LLM through unified
     * search / RAG context assembly.
     */
    public NoteSearchResult searchUnified(String query, int topK, boolean includeLocalOnly, boolean includeSensitive) {
        NoteSearchResult raw = searchUnified(query, topK);
        List<MemoryNoteEntity> filtered = new java.util.ArrayList<>();
        for (MemoryNoteEntity candidate : raw.notes()) {
            if (MemoryService.privacyAllowed(candidate.getPrivacy(), includeLocalOnly, includeSensitive)) {
                filtered.add(candidate);
            }
        }
        return new NoteSearchResult(List.copyOf(filtered), raw.mode());
    }

    /** Edit an existing note in place (only non-null fields are applied), then re-embed. */
    @Transactional
    public MemoryNoteEntity update(String memoryId, MemoryNoteRequest request) {
        MemoryNoteEntity note = repository.findById(memoryId).orElse(null);
        if (note == null) {
            return null;
        }
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            note.setTitle(request.getTitle().trim());
        }
        if (request.getSummary() != null) {
            note.setSummary(request.getSummary());
        }
        if (request.getBody() != null) {
            note.setBody(request.getBody());
        }
        if (request.getCategory() != null) {
            note.setCategory(request.getCategory().name());
        }
        if (request.getTags() != null) {
            note.setTags(new java.util.ArrayList<>(request.getTags()));
        }
        if (request.getScope() != null) {
            note.setScope(request.getScope().name());
        }
        // Roadmap — re-assert the finance/health local-only guard on every edit,
        // whether this request just changed the scope or the note already had a
        // sensitive scope from an earlier write.
        if (isSensitiveScope(MemoryScope.fromString(note.getScope()))) {
            note.setPrivacy("local-only");
        }
        Instant requestedExpiry = resolveExpiresAt(request, Instant.now());
        if (requestedExpiry != null) {
            note.setExpiresAt(requestedExpiry);
        }
        note.setUpdatedAt(Instant.now());
        note = repository.save(note);
        String relativePath = vaultWriter.write(note);
        if (relativePath != null) {
            note.setVaultRelativePath(relativePath);
        }
        note.setEmbedding(embeddingClient.embed(buildEmbeddingInput(note)));
        note = repository.save(note);
        emitAudit(AuditEventType.MEMORY_WRITTEN, note,
                Map.of("category", note.getCategory(), "updated", true));
        return note;
    }

    /**
     * Roadmap #11 — mark/unmark a note as pinned. Pinned notes are excluded from
     * {@link MemoryExpiryCleanupService}'s TTL sweep (see {@link
     * MemoryNoteRepository#findByStatusAndExpiresAtBeforeAndPinnedFalse}) and
     * ranked ahead of non-pinned notes in list/keyword/semantic search. Backs a
     * voice "pin this" / "unpin this" command. Does not touch the vault mirror or
     * re-embed — pin state is UI/voice metadata, not part of the note's content
     * (same "state change without a vault rewrite" precedent as {@link
     * #mergeDuplicate}).
     *
     * @return the updated note, or {@code null} if {@code memoryId} does not exist
     */
    @Transactional
    public MemoryNoteEntity setPinned(String memoryId, boolean pinned) {
        MemoryNoteEntity note = repository.findById(memoryId).orElse(null);
        if (note == null) {
            return null;
        }
        note.setPinned(pinned);
        note.setUpdatedAt(Instant.now());
        note = repository.save(note);
        emitAudit(AuditEventType.MEMORY_WRITTEN, note,
                Map.of("category", note.getCategory(), "pinned", pinned));
        return note;
    }

    /**
     * Roadmap #11 — dedicated "change scope" operation (voice: "move this to
     * &lt;scope&gt;"), distinct from the general {@link #update} PUT: only touches
     * {@code scope} (and the privacy consequence below), never title/body/tags.
     * Re-applies the finance/health local-only privacy guard from wave 4 (see
     * {@link #isSensitiveScope}) exactly like {@link #update} does, so a note
     * moved into {@code FINANCE}/{@code HEALTH} is forced back to {@code
     * local-only} even if it previously had a more permissive privacy value.
     *
     * @return the updated note, or {@code null} if {@code memoryId} does not exist
     * @throws IllegalArgumentException if {@code newScope} is {@code null}
     */
    @Transactional
    public MemoryNoteEntity changeScope(String memoryId, MemoryScope newScope) {
        if (newScope == null) {
            throw new IllegalArgumentException("scope is required");
        }
        MemoryNoteEntity note = repository.findById(memoryId).orElse(null);
        if (note == null) {
            return null;
        }
        note.setScope(newScope.name());
        if (isSensitiveScope(newScope)) {
            note.setPrivacy("local-only");
        }
        if (note.getFrontmatter() != null) {
            note.getFrontmatter().put("privacy", note.getPrivacy());
        }
        note.setUpdatedAt(Instant.now());
        note = repository.save(note);
        String relativePath = vaultWriter.write(note);
        if (relativePath != null) {
            note.setVaultRelativePath(relativePath);
        }
        note = repository.save(note);
        emitAudit(AuditEventType.MEMORY_WRITTEN, note,
                Map.of("category", note.getCategory(), "scopeChanged", true, "newScope", newScope.name()));
        return note;
    }

    /** All active notes for this user — used by the data-export endpoint. */
    public List<MemoryNoteEntity> exportAll() {
        return repository.search(null, "ACTIVE",
                org.springframework.data.domain.PageRequest.of(0, 500));
    }

    /** Roadmap P1 #9 — scope-filtered variant of {@link #exportAll()}. */
    public List<MemoryNoteEntity> exportAll(MemoryScope scope) {
        if (scope == null) {
            return exportAll();
        }
        return repository.searchByCategoryAndScope(null, scope.name(), "ACTIVE",
                org.springframework.data.domain.PageRequest.of(0, 500));
    }

    private void emitAudit(AuditEventType type, MemoryNoteEntity note,
                           Map<String, Object> payload) {
        AuditPublisher publisher = auditProvider.getIfAvailable();
        if (publisher == null) return;
        publisher.audit(type, null, null, null, note.getMemoryId(), payload);
    }

    /**
     * Build the embedding input — title gets priority, then summary,
     * then body. Capped at ~2k chars to keep embedding latency bounded.
     */
    private String buildEmbeddingInput(MemoryNoteEntity note) {
        StringBuilder sb = new StringBuilder();
        if (note.getTitle() != null) sb.append(note.getTitle()).append("\n");
        if (note.getSummary() != null) sb.append(note.getSummary()).append("\n");
        if (note.getBody() != null) sb.append(note.getBody());
        if (sb.length() > 2048) sb.setLength(2048);
        return sb.toString();
    }

    private Map<String, Object> initialFrontmatter(String memoryId,
                                                   MemoryCategory category,
                                                   Instant createdAt,
                                                   String source,
                                                   String privacy,
                                                   List<String> tags,
                                                   List<String> linkedEntities,
                                                   java.math.BigDecimal confidence) {
        LinkedHashMap<String, Object> fm = new LinkedHashMap<>();
        fm.put("type", "memory");
        fm.put("memory_id", memoryId);
        fm.put("category", category.name());
        fm.put("source", source == null ? "jarvis" : source);
        fm.put("created_at", createdAt.toString());
        fm.put("privacy", privacy == null ? "local-only" : privacy);
        fm.put("tags", tags == null ? List.of() : tags);
        fm.put("linked_entities", linkedEntities == null ? List.of() : linkedEntities);
        if (confidence != null) fm.put("confidence", confidence.toPlainString());
        fm.put("status", "active");
        return fm;
    }
}
