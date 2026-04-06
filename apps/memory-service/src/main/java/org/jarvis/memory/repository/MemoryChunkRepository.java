package org.jarvis.memory.repository;

import org.jarvis.memory.entity.MemoryChunk;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MemoryChunkRepository extends JpaRepository<MemoryChunk, UUID> {

    @Query("""
        select c as chunk,
               cosine_distance(c.embedding, :queryEmbedding) as distance
        from MemoryChunk c
        where c.userId = :userId
          and c.embedding is not null
          and cosine_distance(c.embedding, :queryEmbedding) <= :maxDistance
        order by cosine_distance(c.embedding, :queryEmbedding)
        """)
    List<Object[]> findSimilarWithDistance(
            String userId,
            float[] queryEmbedding,
            double maxDistance,
            Pageable pageable);

    /**
     * Find chunks by user
     */
    List<MemoryChunk> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * Count chunks by user
     */
    long countByUserId(String userId);
}
