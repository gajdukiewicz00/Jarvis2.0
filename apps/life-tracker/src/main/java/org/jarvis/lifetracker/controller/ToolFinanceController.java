package org.jarvis.lifetracker.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.dto.BudgetStatusDTO;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.dto.FinanceSummaryDTO;
import org.jarvis.lifetracker.dto.SpendingAnalysisDTO;
import org.jarvis.lifetracker.service.FinanceService;
import org.jarvis.lifetracker.tooling.dto.AnalyzeSpendingToolRequest;
import org.jarvis.lifetracker.tooling.dto.BudgetStatusToolRequest;
import org.jarvis.lifetracker.tooling.dto.ListTransactionsToolRequest;
import org.jarvis.lifetracker.tooling.dto.SummarizeMonthToolRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/tools/finance")
@RequiredArgsConstructor
@Validated
public class ToolFinanceController {

    private final FinanceService financeService;

    @PostMapping("/transactions")
    public ResponseEntity<List<ExpenseDTO>> listTransactions(
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody ListTransactionsToolRequest request) {
        return ResponseEntity.ok(financeService.listTransactions(
                userId,
                request.getFrom(),
                request.getTo(),
                request.getCategory(),
                request.getType()));
    }

    @PostMapping("/summary")
    public ResponseEntity<FinanceSummaryDTO> summarizeMonth(
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody SummarizeMonthToolRequest request) {
        return ResponseEntity.ok(financeService.summarizeMonth(userId, YearMonth.parse(request.getMonth())));
    }

    @PostMapping("/analysis")
    public ResponseEntity<SpendingAnalysisDTO> analyzeSpending(
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody AnalyzeSpendingToolRequest request) {
        return ResponseEntity.ok(financeService.analyzeSpending(
                userId,
                request.getFrom(),
                request.getTo(),
                request.getGroupBy()));
    }

    @PostMapping("/budget-status")
    public ResponseEntity<BudgetStatusDTO> budgetStatus(
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody BudgetStatusToolRequest request) {
        return ResponseEntity.ok(financeService.budgetStatus(userId, YearMonth.parse(request.getMonth())));
    }
}
