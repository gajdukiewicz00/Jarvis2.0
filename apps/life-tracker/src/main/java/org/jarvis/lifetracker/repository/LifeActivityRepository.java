package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.LifeActivityEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/** Phase 12 — durable access to the activity timeline. */
public interface LifeActivityRepository extends JpaRepository<LifeActivityEntry, Long> {

    List<LifeActivityEntry> findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
            String userId, Instant from, Instant to);

    long countByUserId(String userId);
}
