package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.CalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {
    List<CalendarEvent> findByUserId(String userId);

    Optional<CalendarEvent> findByIdAndUserId(Long id, String userId);

    @Query("""
            SELECT e FROM CalendarEvent e
            WHERE e.userId = :userId
              AND e.startTime < :end
              AND (e.endTime IS NULL OR e.endTime > :start)
              AND (:excludeId IS NULL OR e.id <> :excludeId)
            """)
    List<CalendarEvent> findConflicts(
            @Param("userId") String userId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("excludeId") Long excludeId);
}
