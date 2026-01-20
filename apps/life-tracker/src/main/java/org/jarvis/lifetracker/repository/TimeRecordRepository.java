package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.TimeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TimeRecordRepository extends JpaRepository<TimeRecord, Long> {
    List<TimeRecord> findByUserIdOrderByStartTimeDesc(String userId);

    Optional<TimeRecord> findTopByUserIdOrderByEndTimeDesc(String userId);
}
