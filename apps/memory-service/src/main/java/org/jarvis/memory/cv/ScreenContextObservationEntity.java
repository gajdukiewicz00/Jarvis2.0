package org.jarvis.memory.cv;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One persisted screen-context observation. Mirrors the {@code V7} table
 * {@code screen_context_observation}. JSON columns use Hibernate's Jackson
 * mapping; {@code embedding} mirrors the {@code memory_chunk} pgvector setup.
 */
@Entity
@Table(name = "screen_context_observation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScreenContextObservationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "display_server")
    private String displayServer;

    @Column(name = "active_window_title")
    private String activeWindowTitle;

    @Column(name = "active_process_name")
    private String activeProcessName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "semantic_tags", columnDefinition = "jsonb")
    @Builder.Default
    private List<String> semanticTags = List.of();

    @Column(name = "ocr_text", columnDefinition = "TEXT")
    private String ocrText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ocr_blocks", columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> ocrBlocks = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ui_elements", columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> uiElements = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "objects", columnDefinition = "jsonb")
    @Builder.Default
    private List<Map<String, Object>> objects = List.of();

    @Column(name = "screenshot_path")
    private String screenshotPath;

    @Column(name = "screenshot_bytes")
    private byte[] screenshotBytes;

    @Column(name = "engine")
    private String engine;

    @Column(name = "ocr_language")
    private String ocrLanguage;

    @Array(length = 384)
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector(384)")
    private float[] embedding;

    @Column(name = "embedding_model")
    private String embeddingModel;

    @Column(name = "success", nullable = false)
    @Builder.Default
    private boolean success = true;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;
}
