package org.jarvis.lifetracker.repository;

import org.jarvis.lifetracker.domain.Budget;
import org.jarvis.lifetracker.domain.BudgetPeriod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
class BudgetRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BudgetRepository budgetRepository;

    @Test
    void findByUserIdFiltersBudgetsAndPersistsPeriodDatesAndAmount() {
        persistBudget("user-1", "Food", new BigDecimal("300.00"), BudgetPeriod.MONTHLY,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        persistBudget("user-2", "Travel", new BigDecimal("500.00"), BudgetPeriod.YEARLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        entityManager.flush();

        List<Budget> result = budgetRepository.findByUserId("user-1");

        assertEquals(1, result.size());
        Budget budget = result.get(0);
        assertEquals("Food", budget.getCategory());
        assertEquals(BudgetPeriod.MONTHLY, budget.getPeriod());
        assertEquals(0, new BigDecimal("300.00").compareTo(budget.getLimitAmount()));
        assertEquals(LocalDate.of(2026, 3, 1), budget.getStartDate());
        assertEquals(LocalDate.of(2026, 3, 31), budget.getEndDate());
    }

    private void persistBudget(String userId, String category, BigDecimal limit, BudgetPeriod period,
            LocalDate startDate, LocalDate endDate) {
        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setCategory(category);
        budget.setLimitAmount(limit);
        budget.setCurrency("EUR");
        budget.setPeriod(period);
        budget.setStartDate(startDate);
        budget.setEndDate(endDate);
        entityManager.persist(budget);
    }
}
