package org.jarvis.lifetracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.ParsedTransactionDTO;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.service.BankNotificationParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BankNotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class BankNotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BankNotificationParser parser;

    @MockBean
    private ExpenseRepository expenseRepository;

    private ParsedTransactionDTO parsedDto(boolean valid, String confidence, boolean needsReview) {
        return new ParsedTransactionDTO(valid, confidence, needsReview, new BigDecimal("45.99"), "PLN",
                "Lidl", TransactionType.EXPENSE, "groceries", null, "abc123", LocalDateTime.now(),
                "masked text", List.of(), null);
    }

    @Test
    void parseNotificationWithBlankTextReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/life/finance/parse-notification")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "  "}
                                """))
                .andExpect(status().isBadRequest());

        verify(parser, never()).parse(anyString());
    }

    @Test
    void parseNotificationWithoutStoreFlagReturnsDraftWithoutSaving() throws Exception {
        when(parser.parse("Platnosc 45.99 PLN Lidl")).thenReturn(parsedDto(true, "HIGH", false));

        mockMvc.perform(post("/api/v1/life/finance/parse-notification")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Platnosc 45.99 PLN Lidl"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant").value("Lidl"));

        verify(expenseRepository, never()).save(any(Expense.class));
    }

    @Test
    void parseNotificationWithStoreAndHighConfidenceSavesExpense() throws Exception {
        when(parser.parse("Platnosc 45.99 PLN Lidl")).thenReturn(parsedDto(true, "HIGH", false));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(99L);
            return e;
        });

        mockMvc.perform(post("/api/v1/life/finance/parse-notification")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Platnosc 45.99 PLN Lidl", "store": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedId").value(99));

        verify(expenseRepository).save(any(Expense.class));
    }

    @Test
    void parseNotificationWithStoreButLowConfidenceDoesNotSave() throws Exception {
        when(parser.parse("something 12,34")).thenReturn(parsedDto(false, "LOW", true));

        mockMvc.perform(post("/api/v1/life/finance/parse-notification")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "something 12,34", "store": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedId").doesNotExist());

        verify(expenseRepository, never()).save(any(Expense.class));
    }

    @Test
    void parseNotificationWithStoreButMissingUserIdReturnsUnauthorized() throws Exception {
        when(parser.parse("Platnosc 45.99 PLN Lidl")).thenReturn(parsedDto(true, "HIGH", false));

        mockMvc.perform(post("/api/v1/life/finance/parse-notification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "Platnosc 45.99 PLN Lidl", "store": true}
                                """))
                .andExpect(status().isUnauthorized());

        verify(expenseRepository, never()).save(any(Expense.class));
    }

    @Test
    void importCsvNotificationsStoresHighConfidenceAndReturnsLowConfidenceForReview() throws Exception {
        when(parser.parse("Platnosc 45.99 PLN Lidl")).thenReturn(parsedDto(true, "HIGH", false));
        when(parser.parse("something unclear")).thenReturn(parsedDto(false, "LOW", true));
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(1L);
            return e;
        });
        String csv = "text\n\"Platnosc 45.99 PLN Lidl\"\n\"something unclear\"\n";

        mockMvc.perform(post("/api/v1/life/finance/import-csv-notifications")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("csv", csv))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.needsReview.length()").value(1))
                .andExpect(jsonPath("$.totalRows").value(3));

        verify(expenseRepository, times(1)).save(any(Expense.class));
    }

    @Test
    void importCsvNotificationsWithEmptyBodyImportsNothing() throws Exception {
        mockMvc.perform(post("/api/v1/life/finance/import-csv-notifications")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.needsReview.length()").value(0));

        verify(expenseRepository, never()).save(any(Expense.class));
    }
}
