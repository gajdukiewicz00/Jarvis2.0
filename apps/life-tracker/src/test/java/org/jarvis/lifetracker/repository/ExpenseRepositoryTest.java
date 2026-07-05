package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.Expense;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
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

    private Expense createExpenseWithDedupKey(String userId, String dedupKey) {
        Expense expense = createExpense(new BigDecimal("9.99"), "subscriptions");
        expense.setUserId(userId);
        expense.setDedupKey(dedupKey);
        return expense;
    }

    @Test
    void savingSecondExpenseWithSameUserAndDedupKeyThrowsConstraintViolation() {
        expenseRepository.saveAndFlush(createExpenseWithDedupKey(USER_ID, "dedup-shared"));

        Expense duplicate = createExpenseWithDedupKey(USER_ID, "dedup-shared");

        assertThrows(DataIntegrityViolationException.class, () -> expenseRepository.saveAndFlush(duplicate));
    }

    @Test
    void sameDedupKeyIsAllowedAcrossDifferentUsers() {
        expenseRepository.saveAndFlush(createExpenseWithDedupKey("user-a", "dedup-shared-users"));

        Expense otherUsersExpense = createExpenseWithDedupKey("user-b", "dedup-shared-users");

        assertDoesNotThrow(() -> expenseRepository.saveAndFlush(otherUsersExpense));
    }

    @Test
    void multipleNullDedupKeysAreAllowedForTheSameUser() {
        Expense first = createExpense(new BigDecimal("10.00"), "Food");
        Expense second = createExpense(new BigDecimal("20.00"), "Food");

        assertDoesNotThrow(() -> {
            expenseRepository.saveAndFlush(first);
            expenseRepository.saveAndFlush(second);
        });
    }

    @Test
    void findByUserIdAndDedupKeyReturnsMatchingExpense() {
        Expense saved = expenseRepository.saveAndFlush(createExpenseWithDedupKey(USER_ID, "dedup-lookup"));

        Optional<Expense> found = expenseRepository.findByUserIdAndDedupKey(USER_ID, "dedup-lookup");

        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
        assertFalse(expenseRepository.findByUserIdAndDedupKey(USER_ID, "does-not-exist").isPresent());
    }

    @Test
    void existsByUserIdAndDedupKeyReflectsStoredState() {
        assertFalse(expenseRepository.existsByUserIdAndDedupKey(USER_ID, "dedup-exists-check"));

        expenseRepository.saveAndFlush(createExpenseWithDedupKey(USER_ID, "dedup-exists-check"));

        assertTrue(expenseRepository.existsByUserIdAndDedupKey(USER_ID, "dedup-exists-check"));
    }
}
