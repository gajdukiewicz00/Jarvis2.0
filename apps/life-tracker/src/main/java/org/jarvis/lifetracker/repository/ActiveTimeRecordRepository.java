package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.ActiveTimeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ActiveTimeRecordRepository extends JpaRepository<ActiveTimeRecord, Long> {
    Optional<ActiveTimeRecord> findByUserId(String userId);
}
