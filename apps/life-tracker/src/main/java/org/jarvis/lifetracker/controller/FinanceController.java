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

import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

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

    /**
     * Recurring merchant + roughly-stable-amount + roughly-monthly-cadence patterns detected
     * automatically from the user's transaction history (distinct from user-declared
     * {@code /recurring} entries).
     */
    @GetMapping("/subscriptions")
    public List<SubscriptionDTO> listSubscriptions(HttpServletRequest httpRequest) {
        return financeService.detectSubscriptions(requireUserId(httpRequest));
    }

    /**
     * "You're spending faster than budget" pace alerts for the given month: one entry per
     * category budget, flagging both hard overspend and ahead-of-pace spending.
     */
    @GetMapping("/budget/alerts")
    public List<BudgetAlertDTO> budgetAlerts(
            @RequestParam String month,
            HttpServletRequest httpRequest) {
        return financeService.budgetAlerts(requireUserId(httpRequest), YearMonth.parse(month));
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

    @PutMapping("/transaction/{id}")
    public ExpenseDTO updateTransaction(@PathVariable Long id,
                                        @RequestBody TransactionRequest request,
                                        HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        Expense expense = expenseRepository.findById(id)
                .filter(e -> userId.equals(e.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "transaction_not_found"));
        if (request.amount() != null) {
            expense.setAmount(request.amount());
        }
        if (request.currency() != null) {
            expense.setCurrency(request.currency());
        }
        if (request.category() != null) {
            expense.setCategory(request.category());
        }
        if (request.description() != null) {
            expense.setDescription(request.description());
        }
        if (request.type() != null) {
            expense.setType(request.type());
        }
        if (request.occurredAt() != null) {
            expense.setOccurredAt(request.occurredAt());
        }
        return dtoMapper.toDTO(expenseRepository.save(expense));
    }

    @DeleteMapping("/transaction/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable Long id, HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        return expenseRepository.findById(id)
                .filter(e -> userId.equals(e.getUserId()))
                .map(e -> {
                    expenseRepository.delete(e);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Manual quick-add for a cash purchase: no card notification will ever arrive for it, so it
     * needs its own low-friction entry point distinct from {@code /transaction}. Always recorded
     * as an EXPENSE with {@code paymentMethod=CASH}.
     */
    @PostMapping("/cash-expense")
    public ExpenseDTO addCashExpense(@RequestBody CashExpenseRequest request, HttpServletRequest httpRequest) {
        if (request == null || request.amount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount is required");
        }
        String userId = requireUserId(httpRequest);
        Expense expense = new Expense();
        expense.setUserId(userId);
        expense.setAmount(request.amount());
        expense.setCurrency(request.currency() != null ? request.currency() : "EUR");
        expense.setCategory(request.category() != null ? request.category() : "cash");
        expense.setDescription(request.description());
        expense.setType(TransactionType.EXPENSE);
        expense.setPaymentMethod("CASH");
        expense.setOccurredAt(request.occurredAt() != null ? request.occurredAt() : LocalDateTime.now());
        expense.setSource(EntrySource.MANUAL);
        Expense saved = expenseRepository.save(expense);
        return dtoMapper.toDTO(saved);
    }

    /**
     * Import bank transactions from CSV. Each line: {@code date,amount,category,description}
     * (header line is skipped). Amounts are stored as expenses. Returns imported/skipped counts.
     */
    @PostMapping("/import-csv")
    public Map<String, Object> importCsv(@RequestBody Map<String, String> body, HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        String csv = body == null ? "" : body.getOrDefault("csv", "");
        int imported = 0;
        int skipped = 0;
        for (String raw : csv.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.toLowerCase().startsWith("date,")) {
                continue;
            }
            String[] c = line.split(",", 4);
            try {
                if (c.length < 2) {
                    skipped++;
                    continue;
                }
                LocalDate date = LocalDate.parse(c[0].trim());
                BigDecimal amount = new BigDecimal(c[1].trim()).abs();
                Expense expense = new Expense();
                expense.setUserId(userId);
                expense.setAmount(amount);
                expense.setCurrency("EUR");
                expense.setCategory(c.length > 2 && !c[2].isBlank() ? c[2].trim() : "imported");
                expense.setDescription(c.length > 3 ? c[3].trim() : null);
                expense.setType(TransactionType.EXPENSE);
                expense.setOccurredAt(date.atStartOfDay());
                expense.setSource(EntrySource.MANUAL);
                expenseRepository.save(expense);
                imported++;
            } catch (RuntimeException ex) {
                skipped++;
            }
        }
        log.info("CSV import for {}: imported={} skipped={}", userId, imported, skipped);
        return Map.of("imported", imported, "skipped", skipped);
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

    public record CashExpenseRequest(
            BigDecimal amount, String currency, String category, String description, LocalDateTime occurredAt) {
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
