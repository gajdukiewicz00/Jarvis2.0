package org.jarvis.planner.repository;

import org.jarvis.planner.model.DailyPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DailyPlanRepository extends JpaRepository<DailyPlan, Long> {
    
    Optional<DailyPlan> findByUserIdAndPlanDate(String userId, LocalDate planDate);
    
    boolean existsByUserIdAndPlanDate(String userId, LocalDate planDate);
}
