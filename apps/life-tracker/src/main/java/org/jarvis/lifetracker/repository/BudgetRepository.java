package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.Budget;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BudgetRepository extends JpaRepository<Budget, Long> {
    List<Budget> findByUserId(String userId);
}
