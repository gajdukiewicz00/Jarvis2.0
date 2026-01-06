package org.jarvis.lifetracker.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/life/finance")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseRepository expenseRepository;
    private final org.jarvis.lifetracker.service.DTOMapper dtoMapper;

    @PostMapping("/expense")
    public org.jarvis.lifetracker.dto.ExpenseDTO addExpense(@RequestBody ExpenseRequest request) {
        log.info("Adding expense: {}", request);
        Expense expense = new Expense();
        expense.setAmount(java.math.BigDecimal.valueOf(request.amount()));
        expense.setCurrency(request.currency() != null ? request.currency() : "EUR");
        expense.setCategory(request.category());
        expense.setDescription(request.description());
        expense.setDate(LocalDateTime.now());
        Expense saved = expenseRepository.save(expense);
        return dtoMapper.toDTO(saved);
    }

    @GetMapping("/expenses")
    public List<org.jarvis.lifetracker.dto.ExpenseDTO> getExpenses() {
        return expenseRepository.findAll().stream()
                .map(dtoMapper::toDTO)
                .toList();
    }

    public record ExpenseRequest(Double amount, String currency, String category, String description) {
    }
}
