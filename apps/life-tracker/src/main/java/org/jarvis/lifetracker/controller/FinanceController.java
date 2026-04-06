package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.*;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.service.DTOMapper;
import org.jarvis.lifetracker.service.FinanceService;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
            @RequestBody TransactionRequest request,
            HttpServletRequest httpRequest) {
        log.info("Adding transaction: {}", request);
        Expense expense = new Expense();
        expense.setUserId(requireUserId(httpRequest));
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
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) TransactionType type,
            HttpServletRequest httpRequest) {
        return financeService.listTransactions(requireUserId(httpRequest), from, to, category, type);
    }

    @GetMapping("/summary/month")
    public FinanceSummaryDTO summarizeMonth(
            @RequestParam String month,
            HttpServletRequest httpRequest) {
        return financeService.summarizeMonth(requireUserId(httpRequest), YearMonth.parse(month));
    }

    @GetMapping("/analysis/spending")
    public SpendingAnalysisDTO analyzeSpending(
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to,
            @RequestParam(required = false) String groupBy,
            HttpServletRequest httpRequest) {
        return financeService.analyzeSpending(requireUserId(httpRequest), from, to, groupBy);
    }

    @GetMapping("/budget/status")
    public BudgetStatusDTO budgetStatus(
            @RequestParam String month,
            HttpServletRequest httpRequest) {
        return financeService.budgetStatus(requireUserId(httpRequest), YearMonth.parse(month));
    }

    @PostMapping("/budget")
    public BudgetDTO createBudget(@RequestBody BudgetDTO request, HttpServletRequest httpRequest) {
        return financeService.createBudget(requireUserId(httpRequest), request);
    }

    @GetMapping("/budgets")
    public List<BudgetDTO> listBudgets(HttpServletRequest httpRequest) {
        return financeService.listBudgets(requireUserId(httpRequest));
    }

    @PostMapping("/goal")
    public FinancialGoalDTO createGoal(@RequestBody FinancialGoalDTO request, HttpServletRequest httpRequest) {
        return financeService.createGoal(requireUserId(httpRequest), request);
    }

    @GetMapping("/goals")
    public List<FinancialGoalDTO> listGoals(HttpServletRequest httpRequest) {
        return financeService.listGoals(requireUserId(httpRequest));
    }

    @PostMapping("/recurring")
    public RecurringTransactionDTO createRecurring(
            @RequestBody RecurringTransactionDTO request,
            HttpServletRequest httpRequest) {
        return financeService.createRecurring(requireUserId(httpRequest), request);
    }

    @GetMapping("/recurring")
    public List<RecurringTransactionDTO> listRecurring(HttpServletRequest httpRequest) {
        return financeService.listRecurring(requireUserId(httpRequest));
    }

    @PostMapping("/expenses")
    public ExpenseDTO addExpense(
            @RequestBody ExpenseRequest request,
            HttpServletRequest httpRequest) {
        return addTransaction(new TransactionRequest(
                request.userId(),
                BigDecimal.valueOf(request.amount()),
                request.currency(),
                request.category(),
                request.description(),
                TransactionType.EXPENSE,
                null,
                null,
                LocalDateTime.now()), httpRequest);
    }

    @GetMapping("/expenses")
    public List<ExpenseDTO> getExpenses(HttpServletRequest httpRequest) {
        return financeService.listTransactions(requireUserId(httpRequest), null, null, null, null);
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

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
