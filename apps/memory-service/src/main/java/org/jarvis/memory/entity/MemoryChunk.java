package org.jarvis.memory.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
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

    /**
     * Vector embedding (384 dimensions for multilingual-e5-small).
     * Stored as a string representation for native query compatibility.
     * Format: "[0.1, 0.2, ...]"
     */
    @Column(columnDefinition = "vector(384)")
    private String embedding;

    @Column(nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metadata = Map.of();

    /**
     * Convert float array to pgvector string format
     */
    public static String toVectorString(List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(vector.get(i));
        }
        sb.append("]");
        return sb.toString();
    }
}



