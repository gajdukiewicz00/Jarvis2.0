package org.jarvis.memory.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row per /memory/search retrieval. Companion to {@link AuditEventEntity}
 * (which holds privileged WRITE-side audit projected from Kafka). This table
 * captures READ-time retrievals so the desktop panel can answer "which notes
 * did Jarvis read when it produced this answer?".
 *
 * <p>Privacy: {@code queryHash} is the SHA-256 hex digest of the raw query
 * and is always set. {@code queryExcerpt} is optional and gated by
 * {@code logging.pii.allowQuerySnippet} in application.yml. Note bodies are
 * never stored here — only stable identifiers and paths.</p>
 */
@Entity
@Table(name = "memory_search_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MemorySearchAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "query_hash", nullable = false)
    private String queryHash;

    @Column(name = "query_excerpt")
    private String queryExcerpt;

    @Column(name = "selected_model")
    private String selectedModel;

    @Column(name = "retrieval_mode", nullable = false)
    private String retrievalMode;

    @Column(name = "rerank_used", nullable = false)
    private boolean rerankUsed;

    @Column(name = "top_k", nullable = false)
    private int topK;

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @Column(name = "retrieved_note_paths", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> retrievedNotePaths;

    @Column(name = "retrieved_chunk_ids", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> retrievedChunkIds;

    @Column(name = "processing_time_ms")
    private Integer processingTimeMs;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
