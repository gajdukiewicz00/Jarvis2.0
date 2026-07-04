package org.jarvis.memory.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Array;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Entity representing a vectorized chunk of conversation.
 * Used for semantic search in long-term memory.
 */
@Entity
@Table(name = "memory_chunk", indexes = {
    @Index(name = "idx_chunk_user", columnList = "userId"),
    @Index(name = "idx_chunk_created", columnList = "createdAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, columnDefinition = "uuid[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private UUID[] sourceMessageIds;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    @Array(length = 384)
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(columnDefinition = "vector(384)")
    private float[] embedding;

    @Column(nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metadata = Map.of();

    /** B2 — privacy level: public | private | sensitive | local_only. */
    @Column(nullable = false)
    @Builder.Default
    private String privacy = "private";

    public static float[] toPrimitiveArray(java.util.List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            return null;
        }
        float[] values = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            Float value = vector.get(i);
            values[i] = value == null ? 0.0f : value;
        }
        return values;
    }
}


