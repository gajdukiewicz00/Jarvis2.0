package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class ExpenseRepositoryQueryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Test
    void findByUserIdAndOccurredAtBetweenFiltersByUserAndInclusiveDateWindow() {
        LocalDateTime from = LocalDateTime.of(2026, 3, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 3, 31, 23, 59);

        persistExpense("user-1", new BigDecimal("10.00"), "Food", TransactionType.EXPENSE, from);
        persistExpense("user-1", new BigDecimal("20.00"), "Food", TransactionType.EXPENSE, to);
        persistExpense("user-1", new BigDecimal("30.00"), "Food", TransactionType.EXPENSE, from.minusDays(1));
        persistExpense("user-2", new BigDecimal("40.00"), "Food", TransactionType.EXPENSE, from.plusDays(1));
        entityManager.flush();

        List<Expense> result = expenseRepository.findByUserIdAndOccurredAtBetween("user-1", from, to);
        List<String> amounts = result.stream().map(expense -> expense.getAmount().toPlainString()).toList();

        assertEquals(2, result.size());
        assertTrue(amounts.contains("10.00"));
        assertTrue(amounts.contains("20.00"));
    }

    @Test
    void findByUserIdAndTypeReturnsOnlyMatchingTransactionTypeForUser() {
        persistExpense("user-1", new BigDecimal("100.00"), "Salary", TransactionType.INCOME,
                LocalDateTime.of(2026, 3, 5, 9, 0));
        persistExpense("user-1", new BigDecimal("15.00"), "Food", TransactionType.EXPENSE,
                LocalDateTime.of(2026, 3, 5, 12, 0));
        persistExpense("user-2", new BigDecimal("200.00"), "Salary", TransactionType.INCOME,
                LocalDateTime.of(2026, 3, 5, 9, 0));
        entityManager.flush();

        List<Expense> result = expenseRepository.findByUserIdAndType("user-1", TransactionType.INCOME);

        assertEquals(1, result.size());
        assertEquals(TransactionType.INCOME, result.get(0).getType());
        assertEquals(new BigDecimal("100.00"), result.get(0).getAmount());
    }

    @Test
    void findByUserIdAndCategoryIsCurrentlyCaseSensitive() {
        persistExpense("user-1", new BigDecimal("18.00"), "Food", TransactionType.EXPENSE,
                LocalDateTime.of(2026, 3, 7, 18, 0));
        entityManager.flush();

        List<Expense> exactCase = expenseRepository.findByUserIdAndCategory("user-1", "Food");
        List<Expense> lowerCase = expenseRepository.findByUserIdAndCategory("user-1", "food");

        assertEquals(1, exactCase.size());
        assertEquals(0, lowerCase.size());
    }

    private void persistExpense(String userId, BigDecimal amount, String category, TransactionType type,
            LocalDateTime occurredAt) {
        Expense expense = new Expense();
        expense.setUserId(userId);
        expense.setAmount(amount);
        expense.setCurrency("EUR");
        expense.setCategory(category);
        expense.setType(type);
        expense.setOccurredAt(occurredAt);
        entityManager.persist(expense);
    }
}
