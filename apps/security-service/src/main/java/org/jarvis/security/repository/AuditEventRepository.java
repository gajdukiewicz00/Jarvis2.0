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
    // Each optional param is cast to its concrete type in the null-check so Postgres
    // can determine the bind parameter's data type even when the value is null (an
    // untyped NULL in `:param is null` triggers "could not determine data type of
    // parameter" / SQLState 42P18 on PostgreSQL; H2 infers it, which is why this only
    // surfaced against the real DB, not in tests).
    @Query("""
            select e from AuditEvent e
            where (cast(:userId as Long) is null or e.userId = :userId)
              and (cast(:eventType as String) is null or e.eventType = :eventType)
              and (cast(:from as Instant) is null or e.occurredAt >= :from)
              and (cast(:to as Instant) is null or e.occurredAt <= :to)
            """)
    Page<AuditEvent> findFiltered(
            @Param("userId") Long userId,
            @Param("eventType") String eventType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
