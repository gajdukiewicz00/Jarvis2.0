package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.FinancialGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, Long> {
    List<FinancialGoal> findByUserId(String userId);
}
