package org.jarvis.security.repository;

import org.jarvis.security.model.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * Page through audit events, optionally narrowed by user, event type,
     * and/or an occurred-at time range. Any of {@code userId}, {@code
     * eventType}, {@code from}, {@code to} may be {@code null} to skip that
     * predicate entirely.
     */
    @Query("""
            select e from AuditEvent e
            where (:userId is null or e.userId = :userId)
              and (:eventType is null or e.eventType = :eventType)
              and (:from is null or e.occurredAt >= :from)
              and (:to is null or e.occurredAt <= :to)
            """)
    Page<AuditEvent> findFiltered(
            @Param("userId") Long userId,
            @Param("eventType") String eventType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
