package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.RecurringTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecurringTransactionRepository extends JpaRepository<RecurringTransaction, Long> {
    List<RecurringTransaction> findByUserIdAndActiveTrue(String userId);
}
