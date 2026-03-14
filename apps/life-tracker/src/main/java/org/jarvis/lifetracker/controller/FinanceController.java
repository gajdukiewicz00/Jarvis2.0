package org.jarvis.lifetracker.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.*;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.service.DTOMapper;
import org.jarvis.lifetracker.service.FinanceService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/life/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final ExpenseRepository expenseRepository;
    private final DTOMapper dtoMapper;
    private final FinanceService financeService;

    @PostMapping("/transaction")
    public ExpenseDTO addTransaction(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestBody TransactionRequest request) {
        log.info("Adding transaction: {}", request);
        Expense expense = new Expense();
        String userId = headerUserId != null ? headerUserId : request.userId();
        expense.setUserId(userId);
        expense.setAmount(request.amount());
        expense.setCurrency(request.currency() != null ? request.currency() : "EUR");
        expense.setCategory(request.category());
        expense.setDescription(request.description());
        expense.setType(request.type() != null ? request.type() : TransactionType.EXPENSE);
        expense.setMerchant(request.merchant());
        expense.setPaymentMethod(request.paymentMethod());
        expense.setOccurredAt(request.occurredAt() != null ? request.occurredAt() : LocalDateTime.now());
        expense.setSource(EntrySource.MANUAL);
        Expense saved = expenseRepository.save(expense);
        return dtoMapper.toDTO(saved);
    }

    @GetMapping("/transactions")
    public List<ExpenseDTO> getTransactions(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) TransactionType type) {
        String resolvedUserId = headerUserId != null ? headerUserId : userId;
        return financeService.listTransactions(resolvedUserId, from, to, category, type);
    }

    @GetMapping("/summary/month")
    public FinanceSummaryDTO summarizeMonth(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) String userId,
            @RequestParam String month) {
        String resolvedUserId = headerUserId != null ? headerUserId : userId;
        return financeService.summarizeMonth(resolvedUserId, YearMonth.parse(month));
    }

    @GetMapping("/analysis/spending")
    public SpendingAnalysisDTO analyzeSpending(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) String userId,
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to,
            @RequestParam(required = false) String groupBy) {
        String resolvedUserId = headerUserId != null ? headerUserId : userId;
        return financeService.analyzeSpending(resolvedUserId, from, to, groupBy);
    }

    @GetMapping("/budget/status")
    public BudgetStatusDTO budgetStatus(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) String userId,
            @RequestParam String month) {
        String resolvedUserId = headerUserId != null ? headerUserId : userId;
        return financeService.budgetStatus(resolvedUserId, YearMonth.parse(month));
    }

    @PostMapping("/budget")
    public BudgetDTO createBudget(@RequestBody BudgetDTO request) {
        return financeService.createBudget(request);
    }

    @GetMapping("/budgets")
    public List<BudgetDTO> listBudgets(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) String userId) {
        String resolvedUserId = headerUserId != null ? headerUserId : userId;
        return financeService.listBudgets(resolvedUserId);
    }

    @PostMapping("/goal")
    public FinancialGoalDTO createGoal(@RequestBody FinancialGoalDTO request) {
        return financeService.createGoal(request);
    }

    @GetMapping("/goals")
    public List<FinancialGoalDTO> listGoals(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) String userId) {
        String resolvedUserId = headerUserId != null ? headerUserId : userId;
        return financeService.listGoals(resolvedUserId);
    }

    @PostMapping("/recurring")
    public RecurringTransactionDTO createRecurring(@RequestBody RecurringTransactionDTO request) {
        return financeService.createRecurring(request);
    }

    @GetMapping("/recurring")
    public List<RecurringTransactionDTO> listRecurring(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) String userId) {
        String resolvedUserId = headerUserId != null ? headerUserId : userId;
        return financeService.listRecurring(resolvedUserId);
    }

    @PostMapping("/expenses")
    public ExpenseDTO addExpense(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestBody ExpenseRequest request) {
        return addTransaction(headerUserId, new TransactionRequest(
                request.userId(),
                BigDecimal.valueOf(request.amount()),
                request.currency(),
                request.category(),
                request.description(),
                TransactionType.EXPENSE,
                null,
                null,
                LocalDateTime.now()));
    }

    @GetMapping("/expenses")
    public List<ExpenseDTO> getExpenses(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(required = false) String userId) {
        String resolvedUserId = headerUserId != null ? headerUserId : userId;
        return financeService.listTransactions(resolvedUserId, null, null, null, null);
    }

    public record TransactionRequest(
            String userId,
            BigDecimal amount,
            String currency,
            String category,
            String description,
            TransactionType type,
            String merchant,
            String paymentMethod,
            LocalDateTime occurredAt) {
    }

    public record ExpenseRequest(String userId, Double amount, String currency, String category, String description) {
    }
}
