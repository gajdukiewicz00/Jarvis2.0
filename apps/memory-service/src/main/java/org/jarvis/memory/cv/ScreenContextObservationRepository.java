package org.jarvis.memory.cv;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ScreenContextObservationRepository
        extends JpaRepository<ScreenContextObservationEntity, UUID> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Recent observations for a user, newest first. {@code screenshot_bytes}
     * is intentionally not projected here to keep responses small; callers
     * fetch a single row by id if they need the image.
     */
    @Query("""
            select e from ScreenContextObservationEntity e
            where (:userId is null or e.userId = :userId)
            order by e.capturedAt desc nulls last, e.receivedAt desc
            """)
    List<ScreenContextObservationEntity> findRecent(String userId, Pageable pageable);
}
