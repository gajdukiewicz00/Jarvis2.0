package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.Expense;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class ExpenseRepositoryTest {

    private static final String USER_ID = "test-user";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ExpenseRepository expenseRepository;

    @Test
    void shouldSaveAndFindExpense() {
        // Given
        Expense expense = new Expense();
        expense.setAmount(new BigDecimal("100.50"));
        expense.setCurrency("EUR");
        expense.setCategory("Food");
        expense.setDescription("Grocery shopping");
        expense.setUserId(USER_ID);
        expense.setOccurredAt(LocalDateTime.now());

        // When
        Expense saved = expenseRepository.save(expense);
        entityManager.flush();

        // Then
        assertNotNull(saved.getId());
        Expense found = expenseRepository.findById(saved.getId()).orElse(null);
        assertNotNull(found);
        assertEquals(new BigDecimal("100.50"), found.getAmount());
        assertEquals("EUR", found.getCurrency());
        assertEquals("Food", found.getCategory());
    }

    @Test
    void shouldFindAllExpenses() {
        // Given
        Expense expense1 = createExpense(new BigDecimal("50.00"), "Food");
        Expense expense2 = createExpense(new BigDecimal("150.00"), "Transport");
        entityManager.persist(expense1);
        entityManager.persist(expense2);
        entityManager.flush();

        // When
        List<Expense> expenses = expenseRepository.findAll();

        // Then
        assertTrue(expenses.size() >= 2);
    }

    @Test
    void shouldDeleteExpense() {
        // Given
        Expense expense = createExpense(new BigDecimal("75.00"), "Entertainment");
        entityManager.persist(expense);
        entityManager.flush();
        Long expenseId = expense.getId();

        // When
        expenseRepository.deleteById(expenseId);
        entityManager.flush();

        // Then
        assertFalse(expenseRepository.findById(expenseId).isPresent());
    }

    @Test
    void shouldHandleBigDecimalPrecision() {
        // Given
        Expense expense = createExpense(new BigDecimal("99.9999"), "Test");

        // When
        Expense saved = expenseRepository.save(expense);
        entityManager.flush();

        // Then
        Expense found = expenseRepository.findById(saved.getId()).orElse(null);
        assertNotNull(found);
        // BigDecimal should preserve precision
        assertEquals(0, new BigDecimal("99.9999").compareTo(found.getAmount()));
    }

    @Test
    void shouldHandleNullOptionalFields() {
        // Given
        Expense expense = new Expense();
        expense.setAmount(new BigDecimal("10.00"));
        expense.setUserId(USER_ID);
        expense.setOccurredAt(LocalDateTime.now());
        // category, description, currency can be null

        // When
        Expense saved = expenseRepository.save(expense);
        entityManager.flush();

        // Then
        assertNotNull(saved.getId());
    }

    private Expense createExpense(BigDecimal amount, String category) {
        Expense expense = new Expense();
        expense.setAmount(amount);
        expense.setCurrency("EUR");
        expense.setCategory(category);
        expense.setDescription("Test expense");
        expense.setUserId(USER_ID);
        expense.setOccurredAt(LocalDateTime.now());
        return expense;
    }
}
