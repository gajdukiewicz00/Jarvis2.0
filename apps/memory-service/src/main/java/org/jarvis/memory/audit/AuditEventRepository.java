package org.jarvis.memory.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, String> {

    @Query("""
            SELECT a FROM AuditEventEntity a
             WHERE (:since IS NULL OR a.occurredAt >= :since)
               AND (:eventType IS NULL OR a.eventType = :eventType)
               AND (:agentId IS NULL OR a.agentId = :agentId)
               AND (:userId IS NULL OR a.userId = :userId)
             ORDER BY a.occurredAt DESC
            """)
    List<AuditEventEntity> search(@Param("since") Instant since,
                                  @Param("eventType") String eventType,
                                  @Param("agentId") String agentId,
                                  @Param("userId") String userId,
                                  Pageable pageable);
}
