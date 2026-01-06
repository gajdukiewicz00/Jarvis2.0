package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.TimeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeRecordRepository extends JpaRepository<TimeRecord, Long> {
}
