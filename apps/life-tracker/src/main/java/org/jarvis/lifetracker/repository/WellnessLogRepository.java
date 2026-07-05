package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface WellnessLogRepository extends JpaRepository<WellnessLog, Long> {

    List<WellnessLog> findByUserIdAndDayOrderByLoggedAtAsc(String userId, LocalDate day);

    List<WellnessLog> findByUserIdAndTypeOrderByLoggedAtAsc(String userId, WellnessType type);

    List<WellnessLog> findTop200ByUserIdOrderByLoggedAtDesc(String userId);

    List<WellnessLog> findByUserIdAndDayBetweenOrderByLoggedAtAsc(String userId, LocalDate start, LocalDate end);
}
