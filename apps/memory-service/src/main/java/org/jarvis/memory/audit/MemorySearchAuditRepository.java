package org.jarvis.memory.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MemorySearchAuditRepository extends JpaRepository<MemorySearchAuditEntity, UUID> {

    @Query("""
            SELECT a FROM MemorySearchAuditEntity a
             WHERE (:since IS NULL OR a.createdAt >= :since)
               AND (:userId IS NULL OR a.userId = :userId)
             ORDER BY a.createdAt DESC
            """)
    List<MemorySearchAuditEntity> recent(@Param("since") Instant since,
                                         @Param("userId") String userId,
                                         Pageable pageable);
}
