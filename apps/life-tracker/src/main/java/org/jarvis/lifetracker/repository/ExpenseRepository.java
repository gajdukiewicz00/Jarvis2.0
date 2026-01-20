package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByUserId(String userId);

    List<Expense> findByUserIdAndOccurredAtBetween(String userId, LocalDateTime from, LocalDateTime to);

    List<Expense> findByUserIdAndType(String userId, TransactionType type);

    List<Expense> findByUserIdAndCategory(String userId, String category);
}
