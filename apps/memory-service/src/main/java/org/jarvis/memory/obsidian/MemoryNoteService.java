package org.jarvis.memory.obsidian;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
                return update(existing.getMemoryId(), request);
            }
        }
        MemoryCategory category = request.getCategory() == null
                ? MemoryCategory.PROJECTS : request.getCategory();
        Instant now = Instant.now();
        String memoryId = (request.getMemoryId() == null || request.getMemoryId().isBlank())
                ? MemoryNoteEntity.newMemoryId() : request.getMemoryId();

        MemoryNoteEntity note = MemoryNoteEntity.builder()
                .memoryId(memoryId)
                .category(category.name())
                .title(request.getTitle().trim())
                .summary(request.getSummary())
                .body(request.getBody())
                .source(request.getSource() == null ? "jarvis" : request.getSource())
                .privacy(request.getPrivacy() == null ? "local-only" : request.getPrivacy())
                .status("ACTIVE")
                .confidence(request.getConfidence())
                .tags(request.getTags() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(request.getTags()))
                .linkedEntities(request.getLinkedEntities() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(request.getLinkedEntities()))
                .createdAt(now)
                .updatedAt(now)
                .frontmatter(initialFrontmatter(memoryId, category, now,
                        request.getSource(), request.getPrivacy(), request.getTags(),
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
        return note;
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
                    String sql = "SELECT memory_id, title, category, body, vault_relative_path, created_at"
                            + " FROM memory_notes WHERE embedding IS NOT NULL"
                            + " AND (status IS NULL OR status <> 'deleted')"
                            + " AND (embedding <=> CAST(? AS vector)) <= ?"
                            + " ORDER BY embedding <=> CAST(? AS vector) LIMIT " + k;
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

    /** All active notes for this user — used by the data-export endpoint. */
    public List<MemoryNoteEntity> exportAll() {
        return repository.search(null, "ACTIVE",
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
