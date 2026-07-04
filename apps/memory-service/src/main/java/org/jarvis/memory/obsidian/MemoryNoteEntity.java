package org.jarvis.memory.obsidian;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 9 — Postgres source-of-truth row for one memory entry.
 *
 * <p>Mirrored to Obsidian Markdown at {@link #vaultRelativePath} and
 * indexed in pgvector via the {@link #embedding} column.</p>
 */
@Entity
@Table(name = "memory_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MemoryNoteEntity {

    @Id
    @Column(name = "memory_id", nullable = false, length = 64)
    private String memoryId;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "summary")
    private String summary;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "vault_relative_path")
    private String vaultRelativePath;

    @Column(name = "frontmatter", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> frontmatter;

    /**
     * pgvector column (384 dims). Hibernate-vector handles the type.
     * May be {@code null} when embedding-service was unavailable at write
     * time; a re-index job (Phase 12 follow-up) can backfill.
     */
    @Array(length = 384)
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector(384)")
    private float[] embedding;

    @Column(name = "privacy", nullable = false)
    private String privacy;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "confidence")
    private BigDecimal confidence;

    @Column(name = "tags", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> tags;

    @Column(name = "linked_entities", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> linkedEntities;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static String newMemoryId() {
        return "mem-" + java.util.UUID.randomUUID();
    }

    public List<String> tagList() {
        return tags == null ? List.of() : List.copyOf(tags);
    }

    public List<String> linkedEntityList() {
        return linkedEntities == null ? List.of() : List.copyOf(linkedEntities);
    }
}
