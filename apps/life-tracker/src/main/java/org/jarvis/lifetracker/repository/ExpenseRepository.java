package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    List<Expense> findByUserId(String userId);

    List<Expense> findByUserIdAndOccurredAtBetween(String userId, LocalDateTime from, LocalDateTime to);

    List<Expense> findByUserIdAndType(String userId, TransactionType type);

    List<Expense> findByUserIdAndCategory(String userId, String category);

    /** Lookup used for dedup: is a bank-notification draft with this fingerprint already stored? */
    Optional<Expense> findByUserIdAndDedupKey(String userId, String dedupKey);

    boolean existsByUserIdAndDedupKey(String userId, String dedupKey);
}
