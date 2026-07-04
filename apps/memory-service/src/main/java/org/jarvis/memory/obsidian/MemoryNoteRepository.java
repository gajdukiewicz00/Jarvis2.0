package org.jarvis.memory.obsidian;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemoryNoteRepository extends JpaRepository<MemoryNoteEntity, String> {

    @Query("""
            SELECT n FROM MemoryNoteEntity n
             WHERE (:category IS NULL OR n.category = :category)
               AND (:status   IS NULL OR n.status   = :status)
             ORDER BY n.createdAt DESC
            """)
    List<MemoryNoteEntity> search(@Param("category") String category,
                                  @Param("status") String status,
                                  Pageable pageable);

    Optional<MemoryNoteEntity> findByMemoryIdAndStatus(String memoryId, String status);

    /** Newest note with this stable source key (e.g. "obsidian:<path>") — used for upsert. */
    Optional<MemoryNoteEntity> findFirstBySourceOrderByCreatedAtDesc(String source);

    /** Keyword search over note title/body (fallback when embeddings are unavailable). */
    @Query("""
            SELECT n FROM MemoryNoteEntity n
             WHERE (n.status IS NULL OR n.status <> 'deleted')
               AND (LOWER(n.title) LIKE LOWER(CONCAT('%', :q, '%'))
                 OR LOWER(n.body)  LIKE LOWER(CONCAT('%', :q, '%')))
             ORDER BY n.createdAt DESC
            """)
    List<MemoryNoteEntity> searchByText(@Param("q") String q, Pageable pageable);

    /**
     * Semantic search over note embeddings (pgvector cosine distance). Native query
     * with an explicit {@code CAST(:qv AS vector)} so it does not depend on the
     * entity's embedding field being mapped as a Hibernate vector type. {@code qv}
     * is the query vector formatted as a pgvector literal, e.g. {@code [0.1,0.2,...]}.
     */
    @Query(value = """
            SELECT memory_id FROM memory_notes
             WHERE embedding IS NOT NULL
               AND (status IS NULL OR status <> 'deleted')
               AND (embedding <=> CAST(:qv AS vector)) <= :maxDistance
             ORDER BY embedding <=> CAST(:qv AS vector)
             LIMIT :k
            """, nativeQuery = true)
    List<String> findSimilarNoteIds(@Param("qv") String qv,
                                    @Param("maxDistance") double maxDistance,
                                    @Param("k") int k);
}
