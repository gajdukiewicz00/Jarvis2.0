package org.jarvis.lifetracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.BudgetDTO;
import org.jarvis.lifetracker.dto.BudgetStatusDTO;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.dto.FinanceSummaryDTO;
import org.jarvis.lifetracker.dto.FinancialGoalDTO;
import org.jarvis.lifetracker.dto.RecurringTransactionDTO;
import org.jarvis.lifetracker.dto.SpendingAnalysisDTO;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.service.DTOMapper;
import org.jarvis.lifetracker.service.FinanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FinanceController.class)
@AutoConfigureMockMvc(addFilters = false)
class FinanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExpenseRepository expenseRepository;

    @MockBean
    private DTOMapper dtoMapper;

    @MockBean
    private FinanceService financeService;

    @Test
    void addTransactionWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/life/finance/transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10.50}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_user_id"));
    }

    @Test
    void addTransactionAppliesDefaultsAndSaves() throws Exception {
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dtoMapper.toDTO(any(Expense.class))).thenReturn(new ExpenseDTO());

        mockMvc.perform(post("/api/v1/life/finance/transaction")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 10.50}
                                """))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Expense> captor = org.mockito.ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(captor.capture());
        Expense saved = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(saved.getUserId()).isEqualTo("user-1");
        org.assertj.core.api.Assertions.assertThat(saved.getCurrency()).isEqualTo("EUR");
        org.assertj.core.api.Assertions.assertThat(saved.getType()).isEqualTo(TransactionType.EXPENSE);
        org.assertj.core.api.Assertions.assertThat(saved.getSource()).isEqualTo(EntrySource.MANUAL);
    }

    @Test
    void getTransactionsDelegatesToFinanceService() throws Exception {
        when(financeService.listTransactions(eq("user-1"), any(), any(), any(), any()))
                .thenReturn(List.of(new ExpenseDTO()));

        mockMvc.perform(get("/api/v1/life/finance/transactions")
                        .header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void summarizeMonthDelegatesToFinanceService() throws Exception {
        when(financeService.summarizeMonth(eq("user-1"), eq(YearMonth.parse("2026-03"))))
                .thenReturn(new FinanceSummaryDTO("2026-03", BigDecimal.TEN, BigDecimal.ONE, "EUR", Map.of()));

        mockMvc.perform(get("/api/v1/life/finance/summary/month")
                        .header("X-User-Id", "user-1")
                        .param("month", "2026-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-03"));
    }

    @Test
    void analyzeSpendingDelegatesToFinanceService() throws Exception {
        when(financeService.analyzeSpending(eq("user-1"), any(), any(), eq("MERCHANT")))
                .thenReturn(new SpendingAnalysisDTO(null, null, "MERCHANT", List.of()));

        mockMvc.perform(get("/api/v1/life/finance/analysis/spending")
                        .header("X-User-Id", "user-1")
                        .param("from", "2026-03-01T00:00:00")
                        .param("to", "2026-03-31T23:59:00")
                        .param("groupBy", "MERCHANT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBy").value("MERCHANT"));
    }

    @Test
    void budgetStatusDelegatesToFinanceService() throws Exception {
        when(financeService.budgetStatus(eq("user-1"), eq(YearMonth.parse("2026-03"))))
                .thenReturn(new BudgetStatusDTO("2026-03", List.of()));

        mockMvc.perform(get("/api/v1/life/finance/budget/status")
                        .header("X-User-Id", "user-1")
                        .param("month", "2026-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-03"));
    }

    @Test
    void createBudgetDelegatesToFinanceService() throws Exception {
        when(financeService.createBudget(eq("user-1"), any(BudgetDTO.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        mockMvc.perform(post("/api/v1/life/finance/budget")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category": "Food", "limitAmount": 300.00, "currency": "EUR", "period": "MONTHLY"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("Food"));
    }

    @Test
    void listBudgetsDelegatesToFinanceService() throws Exception {
        when(financeService.listBudgets("user-1")).thenReturn(List.of(new BudgetDTO()));

        mockMvc.perform(get("/api/v1/life/finance/budgets").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void createGoalDelegatesToFinanceService() throws Exception {
        when(financeService.createGoal(eq("user-1"), any(FinancialGoalDTO.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        mockMvc.perform(post("/api/v1/life/finance/goal")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Vacation", "targetAmount": 2000.00}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Vacation"));
    }

    @Test
    void listGoalsDelegatesToFinanceService() throws Exception {
        when(financeService.listGoals("user-1")).thenReturn(List.of(new FinancialGoalDTO()));

        mockMvc.perform(get("/api/v1/life/finance/goals").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void createRecurringDelegatesToFinanceService() throws Exception {
        when(financeService.createRecurring(eq("user-1"), any(RecurringTransactionDTO.class)))
                .thenAnswer(inv -> inv.getArgument(1));

        mockMvc.perform(post("/api/v1/life/finance/recurring")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 9.99, "category": "Subscriptions"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("Subscriptions"));
    }

    @Test
    void listRecurringDelegatesToFinanceService() throws Exception {
        when(financeService.listRecurring("user-1")).thenReturn(List.of(new RecurringTransactionDTO()));

        mockMvc.perform(get("/api/v1/life/finance/recurring").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void addExpenseDelegatesToAddTransactionWithExpenseDefaults() throws Exception {
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dtoMapper.toDTO(any(Expense.class))).thenReturn(new ExpenseDTO());

        mockMvc.perform(post("/api/v1/life/finance/expenses")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 12.5, "currency": "EUR", "category": "Food", "description": "lunch"}
                                """))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Expense> captor = org.mockito.ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(12.5));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getType()).isEqualTo(TransactionType.EXPENSE);
    }

    @Test
    void getExpensesDelegatesToFinanceServiceWithNullFilters() throws Exception {
        when(financeService.listTransactions(eq("user-1"), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(new ExpenseDTO()));

        mockMvc.perform(get("/api/v1/life/finance/expenses").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void updateTransactionReturnsNotFoundForOtherUsersExpense() throws Exception {
        Expense other = new Expense();
        other.setUserId("other-user");
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(other));

        mockMvc.perform(put("/api/v1/life/finance/transaction/1")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("transaction_not_found"));
    }

    @Test
    void updateTransactionUpdatesProvidedFieldsOnly() throws Exception {
        Expense existing = new Expense();
        existing.setUserId("user-1");
        existing.setAmount(new BigDecimal("5.00"));
        existing.setCurrency("USD");
        existing.setCategory("Old");
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dtoMapper.toDTO(any(Expense.class))).thenReturn(new ExpenseDTO());

        mockMvc.perform(put("/api/v1/life/finance/transaction/1")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 25.00, "category": "New"}
                                """))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Expense> captor = org.mockito.ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getAmount()).isEqualByComparingTo("25.00");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCategory()).isEqualTo("New");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void deleteTransactionReturnsNotFoundWhenMissing() throws Exception {
        when(expenseRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/life/finance/transaction/99").header("X-User-Id", "user-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteTransactionDeletesAndReturnsNoContent() throws Exception {
        Expense existing = new Expense();
        existing.setUserId("user-1");
        when(expenseRepository.findById(1L)).thenReturn(Optional.of(existing));

        mockMvc.perform(delete("/api/v1/life/finance/transaction/1").header("X-User-Id", "user-1"))
                .andExpect(status().isNoContent());

        verify(expenseRepository).delete(existing);
    }

    @Test
    void importCsvImportsValidLinesAndSkipsInvalidOnes() throws Exception {
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        String csv = "date,amount,category,description\n"
                + "2026-03-01,15.50,Food,lunch\n"
                + "not-a-date,oops\n";

        mockMvc.perform(post("/api/v1/life/finance/import-csv")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("csv", csv))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(1));

        verify(expenseRepository, times(1)).save(any(Expense.class));
    }

    @Test
    void importCsvWithEmptyBodyImportsNothing() throws Exception {
        mockMvc.perform(post("/api/v1/life/finance/import-csv")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.skipped").value(0));

        verify(expenseRepository, never()).save(any(Expense.class));
    }
}
