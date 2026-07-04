package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.Budget;
import org.jarvis.lifetracker.domain.BudgetPeriod;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.FinancialGoal;
import org.jarvis.lifetracker.domain.GoalStatus;
import org.jarvis.lifetracker.domain.RecurringInterval;
import org.jarvis.lifetracker.domain.RecurringTransaction;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.BudgetDTO;
import org.jarvis.lifetracker.dto.BudgetStatusDTO;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.dto.FinanceSummaryDTO;
import org.jarvis.lifetracker.dto.FinancialGoalDTO;
import org.jarvis.lifetracker.dto.RecurringTransactionDTO;
import org.jarvis.lifetracker.dto.SpendingAnalysisDTO;
import org.jarvis.lifetracker.repository.BudgetRepository;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.repository.FinancialGoalRepository;
import org.jarvis.lifetracker.repository.RecurringTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceServiceTest {

    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private BudgetRepository budgetRepository;
    @Mock
    private FinancialGoalRepository financialGoalRepository;
    @Mock
    private RecurringTransactionRepository recurringTransactionRepository;
    @Mock
    private DTOMapper dtoMapper;

    private FinanceService financeService;

    @BeforeEach
    void setUp() {
        financeService = new FinanceService(
                expenseRepository, budgetRepository, financialGoalRepository,
                recurringTransactionRepository, dtoMapper);
    }

    private Expense expense(BigDecimal amount, TransactionType type, String category, String currency) {
        Expense e = new Expense();
        e.setAmount(amount);
        e.setType(type);
        e.setCategory(category);
        e.setCurrency(currency);
        e.setMerchant("Merchant-" + category);
        e.setOccurredAt(LocalDateTime.of(2026, 3, 10, 12, 0));
        return e;
    }

    @Test
    void listTransactionsWithDateRangeUsesBetweenQueryAndAppliesFilters() {
        Expense groceries = expense(new BigDecimal("10.00"), TransactionType.EXPENSE, "Groceries", "EUR");
        Expense salary = expense(new BigDecimal("2000.00"), TransactionType.INCOME, "Salary", "EUR");
        LocalDateTime from = LocalDateTime.of(2026, 3, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 3, 31, 23, 59);
        when(expenseRepository.findByUserIdAndOccurredAtBetween("user-1", from, to))
                .thenReturn(List.of(groceries, salary));
        when(dtoMapper.toDTO(any(Expense.class))).thenAnswer(inv -> new ExpenseDTO());

        List<ExpenseDTO> result = financeService.listTransactions(
                "user-1", from, to, "groceries", TransactionType.EXPENSE);

        assertThat(result).hasSize(1);
        verify(expenseRepository).findByUserIdAndOccurredAtBetween("user-1", from, to);
        verify(expenseRepository, org.mockito.Mockito.never()).findByUserId(anyString());
    }

    @Test
    void listTransactionsWithoutDateRangeUsesFindByUserId() {
        Expense groceries = expense(new BigDecimal("10.00"), TransactionType.EXPENSE, "Groceries", "EUR");
        when(expenseRepository.findByUserId("user-1")).thenReturn(List.of(groceries));
        when(dtoMapper.toDTO(any(Expense.class))).thenReturn(new ExpenseDTO());

        List<ExpenseDTO> result = financeService.listTransactions("user-1", null, null, null, null);

        assertThat(result).hasSize(1);
        verify(expenseRepository).findByUserId("user-1");
    }

    @Test
    void summarizeMonthAggregatesIncomeExpenseAndCategoryTotals() {
        YearMonth month = YearMonth.of(2026, 3);
        Expense income = expense(new BigDecimal("1500.00"), TransactionType.INCOME, "Salary", "EUR");
        Expense groceries = expense(new BigDecimal("40.00"), TransactionType.EXPENSE, "Groceries", "EUR");
        Expense uncategorized = expense(new BigDecimal("15.00"), TransactionType.EXPENSE, null, null);
        when(expenseRepository.findByUserIdAndOccurredAtBetween(
                eq("user-1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(income, groceries, uncategorized));

        FinanceSummaryDTO summary = financeService.summarizeMonth("user-1", month);

        assertThat(summary.getMonth()).isEqualTo("2026-03");
        assertThat(summary.getTotalIncome()).isEqualByComparingTo("1500.00");
        assertThat(summary.getTotalExpense()).isEqualByComparingTo("55.00");
        assertThat(summary.getCurrency()).isEqualTo("EUR");
        assertThat(summary.getByCategory()).containsEntry("Groceries", new BigDecimal("40.00"));
        assertThat(summary.getByCategory()).containsEntry("Uncategorized", new BigDecimal("15.00"));
    }

    @Test
    void summarizeMonthDefaultsCurrencyToEurWhenNoneRecorded() {
        YearMonth month = YearMonth.of(2026, 4);
        when(expenseRepository.findByUserIdAndOccurredAtBetween(
                eq("user-1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        FinanceSummaryDTO summary = financeService.summarizeMonth("user-1", month);

        assertThat(summary.getCurrency()).isEqualTo("EUR");
        assertThat(summary.getTotalIncome()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.getTotalExpense()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.getByCategory()).isEmpty();
    }

    @Test
    void analyzeSpendingGroupsByCategoryByDefault() {
        LocalDateTime from = LocalDateTime.of(2026, 3, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 3, 31, 23, 59);
        Expense groceries1 = expense(new BigDecimal("10.00"), TransactionType.EXPENSE, "Groceries", "EUR");
        Expense groceries2 = expense(new BigDecimal("20.00"), TransactionType.EXPENSE, "Groceries", "EUR");
        Expense income = expense(new BigDecimal("999.00"), TransactionType.INCOME, "Salary", "EUR");
        when(expenseRepository.findByUserIdAndOccurredAtBetween("user-1", from, to))
                .thenReturn(List.of(groceries1, groceries2, income));

        SpendingAnalysisDTO analysis = financeService.analyzeSpending("user-1", from, to, null);

        assertThat(analysis.getGroupBy()).isEqualTo("CATEGORY");
        assertThat(analysis.getBuckets()).hasSize(1);
        assertThat(analysis.getBuckets().get(0).getKey()).isEqualTo("Groceries");
        assertThat(analysis.getBuckets().get(0).getTotal()).isEqualByComparingTo("30.00");
        assertThat(analysis.getBuckets().get(0).getCount()).isEqualTo(2);
    }

    @Test
    void analyzeSpendingGroupsByMerchantWhenRequested() {
        LocalDateTime from = LocalDateTime.of(2026, 3, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 3, 31, 23, 59);
        Expense e1 = expense(new BigDecimal("10.00"), TransactionType.EXPENSE, "Groceries", "EUR");
        e1.setMerchant("Lidl");
        when(expenseRepository.findByUserIdAndOccurredAtBetween("user-1", from, to))
                .thenReturn(List.of(e1));

        SpendingAnalysisDTO analysis = financeService.analyzeSpending("user-1", from, to, "merchant");

        assertThat(analysis.getGroupBy()).isEqualTo("MERCHANT");
        assertThat(analysis.getBuckets()).hasSize(1);
        assertThat(analysis.getBuckets().get(0).getKey()).isEqualTo("Lidl");
        assertThat(analysis.getFrom()).isEqualTo(from.toString());
        assertThat(analysis.getTo()).isEqualTo(to.toString());
    }

    @Test
    void budgetStatusMarksOverAndOkCorrectly() {
        YearMonth month = YearMonth.of(2026, 3);
        Budget foodBudget = new Budget();
        foodBudget.setCategory("Food");
        foodBudget.setLimitAmount(new BigDecimal("50.00"));
        foodBudget.setCurrency("EUR");
        Budget travelBudget = new Budget();
        travelBudget.setCategory("Travel");
        travelBudget.setLimitAmount(new BigDecimal("100.00"));
        travelBudget.setCurrency("EUR");

        Expense foodExpense = expense(new BigDecimal("75.00"), TransactionType.EXPENSE, "Food", "EUR");
        when(expenseRepository.findByUserIdAndOccurredAtBetween(
                eq("user-1"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(foodExpense));
        when(budgetRepository.findByUserId("user-1")).thenReturn(List.of(foodBudget, travelBudget));

        BudgetStatusDTO status = financeService.budgetStatus("user-1", month);

        assertThat(status.getMonth()).isEqualTo("2026-03");
        assertThat(status.getBudgets()).hasSize(2);
        assertThat(status.getBudgets().get(0).getStatus()).isEqualTo("OVER");
        assertThat(status.getBudgets().get(0).getSpentAmount()).isEqualByComparingTo("75.00");
        assertThat(status.getBudgets().get(1).getStatus()).isEqualTo("OK");
        assertThat(status.getBudgets().get(1).getSpentAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createBudgetPersistsAndMapsToDto() {
        BudgetDTO request = new BudgetDTO(null, null, "Food", new BigDecimal("300.00"), "EUR",
                BudgetPeriod.MONTHLY, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null);
        when(budgetRepository.save(any(Budget.class))).thenAnswer(inv -> {
            Budget b = inv.getArgument(0);
            b.setId(5L);
            return b;
        });

        BudgetDTO result = financeService.createBudget("user-1", request);

        ArgumentCaptor<Budget> captor = ArgumentCaptor.forClass(Budget.class);
        verify(budgetRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getCategory()).isEqualTo("Food");
        assertThat(result.getId()).isEqualTo(5L);
        assertThat(result.getCategory()).isEqualTo("Food");
    }

    @Test
    void listBudgetsMapsAllEntities() {
        Budget b = new Budget();
        b.setId(1L);
        b.setUserId("user-1");
        b.setCategory("Food");
        b.setLimitAmount(new BigDecimal("100.00"));
        when(budgetRepository.findByUserId("user-1")).thenReturn(List.of(b));

        List<BudgetDTO> result = financeService.listBudgets("user-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory()).isEqualTo("Food");
    }

    @Test
    void createGoalPersistsAndMapsToDto() {
        FinancialGoalDTO request = new FinancialGoalDTO(null, null, "Vacation", new BigDecimal("2000.00"),
                new BigDecimal("500.00"), LocalDate.of(2026, 12, 1), GoalStatus.ACTIVE, null, null);
        when(financialGoalRepository.save(any(FinancialGoal.class))).thenAnswer(inv -> {
            FinancialGoal g = inv.getArgument(0);
            g.setId(9L);
            return g;
        });

        FinancialGoalDTO result = financeService.createGoal("user-1", request);

        ArgumentCaptor<FinancialGoal> captor = ArgumentCaptor.forClass(FinancialGoal.class);
        verify(financialGoalRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getName()).isEqualTo("Vacation");
        assertThat(result.getId()).isEqualTo(9L);
        assertThat(result.getName()).isEqualTo("Vacation");
    }

    @Test
    void listGoalsMapsAllEntities() {
        FinancialGoal g = new FinancialGoal();
        g.setId(2L);
        g.setUserId("user-1");
        g.setName("Vacation");
        when(financialGoalRepository.findByUserId("user-1")).thenReturn(List.of(g));

        List<FinancialGoalDTO> result = financeService.listGoals("user-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Vacation");
    }

    @Test
    void createRecurringPersistsAndMapsToDto() {
        RecurringTransactionDTO request = new RecurringTransactionDTO(null, null, new BigDecimal("9.99"),
                "EUR", "Subscriptions", "Netflix", TransactionType.EXPENSE, "Netflix",
                RecurringInterval.MONTHLY, LocalDateTime.of(2026, 4, 1, 0, 0), true, null, null);
        when(recurringTransactionRepository.save(any(RecurringTransaction.class))).thenAnswer(inv -> {
            RecurringTransaction r = inv.getArgument(0);
            r.setId(3L);
            return r;
        });

        RecurringTransactionDTO result = financeService.createRecurring("user-1", request);

        ArgumentCaptor<RecurringTransaction> captor = ArgumentCaptor.forClass(RecurringTransaction.class);
        verify(recurringTransactionRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(captor.getValue().getMerchant()).isEqualTo("Netflix");
        assertThat(result.getId()).isEqualTo(3L);
        assertThat(result.isActive()).isTrue();
    }

    @Test
    void listRecurringReturnsOnlyActiveEntitiesMapped() {
        RecurringTransaction r = new RecurringTransaction();
        r.setId(4L);
        r.setUserId("user-1");
        r.setActive(true);
        when(recurringTransactionRepository.findByUserIdAndActiveTrue("user-1")).thenReturn(List.of(r));

        List<RecurringTransactionDTO> result = financeService.listRecurring("user-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(4L);
        assertThat(result.get(0).isActive()).isTrue();
    }
}
