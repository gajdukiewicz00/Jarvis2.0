package org.jarvis.lifetracker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.Budget;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.FinancialGoal;
import org.jarvis.lifetracker.domain.RecurringTransaction;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.*;
import org.jarvis.lifetracker.repository.BudgetRepository;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.repository.FinancialGoalRepository;
import org.jarvis.lifetracker.repository.RecurringTransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinanceService {

    private final ExpenseRepository expenseRepository;
    private final BudgetRepository budgetRepository;
    private final FinancialGoalRepository financialGoalRepository;
    private final RecurringTransactionRepository recurringTransactionRepository;
    private final DTOMapper dtoMapper;

    public List<ExpenseDTO> listTransactions(String userId, LocalDateTime from, LocalDateTime to,
            String category, TransactionType type) {
        List<Expense> expenses;
        if (from != null && to != null) {
            expenses = expenseRepository.findByUserIdAndOccurredAtBetween(userId, from, to);
        } else {
            expenses = expenseRepository.findByUserId(userId);
        }
        return expenses.stream()
                .filter(expense -> category == null || category.equalsIgnoreCase(expense.getCategory()))
                .filter(expense -> type == null || type == expense.getType())
                .map(dtoMapper::toDTO)
                .toList();
    }

    public FinanceSummaryDTO summarizeMonth(String userId, YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.atEndOfMonth().atTime(23, 59, 59);
        List<Expense> expenses = expenseRepository.findByUserIdAndOccurredAtBetween(userId, from, to);

        BigDecimal totalIncome = sumAmount(expenses, TransactionType.INCOME);
        BigDecimal totalExpense = sumAmount(expenses, TransactionType.EXPENSE);
        String currency = expenses.stream().map(Expense::getCurrency).filter(value -> value != null && !value.isBlank())
                .findFirst().orElse("EUR");

        Map<String, BigDecimal> byCategory = expenses.stream()
                .filter(expense -> expense.getType() == TransactionType.EXPENSE)
                .collect(Collectors.groupingBy(
                        expense -> expense.getCategory() != null ? expense.getCategory() : "Uncategorized",
                        Collectors.reducing(BigDecimal.ZERO, Expense::getAmount, BigDecimal::add)));

        return new FinanceSummaryDTO(month.toString(), totalIncome, totalExpense, currency, byCategory);
    }

    public SpendingAnalysisDTO analyzeSpending(String userId, LocalDateTime from, LocalDateTime to, String groupBy) {
        List<Expense> expenses = expenseRepository.findByUserIdAndOccurredAtBetween(userId, from, to).stream()
                .filter(expense -> expense.getType() == TransactionType.EXPENSE)
                .toList();

        Map<String, List<Expense>> grouped = expenses.stream()
                .collect(Collectors.groupingBy(expense -> {
                    if ("MERCHANT".equalsIgnoreCase(groupBy)) {
                        return expense.getMerchant() != null ? expense.getMerchant() : "Unknown";
                    }
                    return expense.getCategory() != null ? expense.getCategory() : "Uncategorized";
                }));

        List<SpendingBucketDTO> buckets = grouped.entrySet().stream()
                .map(entry -> new SpendingBucketDTO(
                        entry.getKey(),
                        entry.getValue().stream().map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                        entry.getValue().size()))
                .sorted(Comparator.comparing(SpendingBucketDTO::getTotal).reversed())
                .toList();

        return new SpendingAnalysisDTO(
                from != null ? from.toString() : null,
                to != null ? to.toString() : null,
                groupBy != null ? groupBy.toUpperCase() : "CATEGORY",
                buckets);
    }

    public BudgetStatusDTO budgetStatus(String userId, YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.atEndOfMonth().atTime(23, 59, 59);
        List<Expense> expenses = expenseRepository.findByUserIdAndOccurredAtBetween(userId, from, to);
        List<BudgetUsageDTO> budgets = budgetRepository.findByUserId(userId).stream()
                .map(budget -> {
                    BigDecimal spent = expenses.stream()
                            .filter(expense -> expense.getType() == TransactionType.EXPENSE)
                            .filter(expense -> budget.getCategory().equalsIgnoreCase(expense.getCategory()))
                            .map(Expense::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    String status = spent.compareTo(budget.getLimitAmount()) > 0 ? "OVER" : "OK";
                    return new BudgetUsageDTO(
                            budget.getCategory(),
                            budget.getLimitAmount(),
                            spent,
                            budget.getCurrency(),
                            status);
                })
                .toList();

        return new BudgetStatusDTO(month.toString(), budgets);
    }

    public BudgetDTO createBudget(String userId, BudgetDTO dto) {
        Budget budget = new Budget();
        budget.setUserId(userId);
        budget.setCategory(dto.getCategory());
        budget.setLimitAmount(dto.getLimitAmount());
        budget.setCurrency(dto.getCurrency());
        budget.setPeriod(dto.getPeriod());
        budget.setStartDate(dto.getStartDate());
        budget.setEndDate(dto.getEndDate());
        Budget saved = budgetRepository.save(budget);
        return toDto(saved);
    }

    public List<BudgetDTO> listBudgets(String userId) {
        return budgetRepository.findByUserId(userId).stream()
                .map(this::toDto)
                .toList();
    }

    public FinancialGoalDTO createGoal(String userId, FinancialGoalDTO dto) {
        FinancialGoal goal = new FinancialGoal();
        goal.setUserId(userId);
        goal.setName(dto.getName());
        goal.setTargetAmount(dto.getTargetAmount());
        goal.setCurrentAmount(dto.getCurrentAmount());
        goal.setTargetDate(dto.getTargetDate());
        goal.setStatus(dto.getStatus());
        FinancialGoal saved = financialGoalRepository.save(goal);
        return toDto(saved);
    }

    public List<FinancialGoalDTO> listGoals(String userId) {
        return financialGoalRepository.findByUserId(userId).stream()
                .map(this::toDto)
                .toList();
    }

    public RecurringTransactionDTO createRecurring(String userId, RecurringTransactionDTO dto) {
        RecurringTransaction transaction = new RecurringTransaction();
        transaction.setUserId(userId);
        transaction.setAmount(dto.getAmount());
        transaction.setCurrency(dto.getCurrency());
        transaction.setCategory(dto.getCategory());
        transaction.setDescription(dto.getDescription());
        transaction.setType(dto.getType());
        transaction.setMerchant(dto.getMerchant());
        transaction.setInterval(dto.getInterval());
        transaction.setNextRun(dto.getNextRun());
        transaction.setActive(dto.isActive());
        RecurringTransaction saved = recurringTransactionRepository.save(transaction);
        return toDto(saved);
    }

    public List<RecurringTransactionDTO> listRecurring(String userId) {
        return recurringTransactionRepository.findByUserIdAndActiveTrue(userId).stream()
                .map(this::toDto)
                .toList();
    }

    private BigDecimal sumAmount(List<Expense> expenses, TransactionType type) {
        return expenses.stream()
                .filter(expense -> expense.getType() == type)
                .map(Expense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BudgetDTO toDto(Budget budget) {
        return new BudgetDTO(
                budget.getId(),
                budget.getUserId(),
                budget.getCategory(),
                budget.getLimitAmount(),
                budget.getCurrency(),
                budget.getPeriod(),
                budget.getStartDate(),
                budget.getEndDate(),
                budget.getCreatedAt(),
                budget.getUpdatedAt());
    }

    private FinancialGoalDTO toDto(FinancialGoal goal) {
        return new FinancialGoalDTO(
                goal.getId(),
                goal.getUserId(),
                goal.getName(),
                goal.getTargetAmount(),
                goal.getCurrentAmount(),
                goal.getTargetDate(),
                goal.getStatus(),
                goal.getCreatedAt(),
                goal.getUpdatedAt());
    }

    private RecurringTransactionDTO toDto(RecurringTransaction recurring) {
        return new RecurringTransactionDTO(
                recurring.getId(),
                recurring.getUserId(),
                recurring.getAmount(),
                recurring.getCurrency(),
                recurring.getCategory(),
                recurring.getDescription(),
                recurring.getType(),
                recurring.getMerchant(),
                recurring.getInterval(),
                recurring.getNextRun(),
                recurring.isActive(),
                recurring.getCreatedAt(),
                recurring.getUpdatedAt());
    }
}
