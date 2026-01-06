package org.jarvis.memory.repository;

import org.jarvis.memory.entity.MemoryChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoryChunkRepository extends JpaRepository<MemoryChunk, UUID> {

    /**
     * Find similar chunks using pgvector cosine distance.
     * Lower distance = more similar.
     * 
     * @param userId User ID to filter by
     * @param queryVector Query embedding as pgvector string format "[0.1, 0.2, ...]"
     * @param topK Maximum number of results
     * @return List of similar chunks ordered by similarity
     */
    @Query(value = """
        SELECT * FROM memory_chunk 
        WHERE user_id = :userId 
        AND embedding IS NOT NULL
        ORDER BY embedding <=> cast(:queryVector as vector) 
        LIMIT :topK
        """, nativeQuery = true)
    List<MemoryChunk> findSimilar(
            @Param("userId") String userId,
            @Param("queryVector") String queryVector,
            @Param("topK") int topK);

    /**
     * Find similar chunks with similarity score.
     * Uses 1 - cosine_distance as similarity (higher = more similar).
     */
    @Query(value = """
        SELECT mc.*, 
               1 - (embedding <=> cast(:queryVector as vector)) as similarity
        FROM memory_chunk mc
        WHERE user_id = :userId 
        AND embedding IS NOT NULL
        AND 1 - (embedding <=> cast(:queryVector as vector)) >= :minSimilarity
        ORDER BY embedding <=> cast(:queryVector as vector) 
        LIMIT :topK
        """, nativeQuery = true)
    List<Object[]> findSimilarWithScore(
            @Param("userId") String userId,
            @Param("queryVector") String queryVector,
            @Param("topK") int topK,
            @Param("minSimilarity") double minSimilarity);

    /**
     * Find chunks by user
     */
    List<MemoryChunk> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * Count chunks by user
     */
    long countByUserId(String userId);

    /**
     * Delete chunks by source message IDs
     */
    @Query("DELETE FROM MemoryChunk c WHERE :messageId = ANY(c.sourceMessageIds)")
    void deleteBySourceMessageId(@Param("messageId") UUID messageId);
}



