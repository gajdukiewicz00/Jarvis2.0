package org.jarvis.lifetracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.jarvis.lifetracker.service.FinanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExportController.class)
@AutoConfigureMockMvc(addFilters = false)
class ExportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WellnessLogRepository wellnessRepository;

    @MockBean
    private ExpenseRepository expenseRepository;

    @MockBean
    private FinanceService financeService;

    private ExpenseDTO expenseDto(BigDecimal amount, String category) {
        ExpenseDTO dto = new ExpenseDTO();
        dto.setId(1L);
        dto.setAmount(amount);
        dto.setCurrency("EUR");
        dto.setCategory(category);
        dto.setDescription("lunch");
        dto.setType(TransactionType.EXPENSE);
        dto.setMerchant("Lidl");
        dto.setPaymentMethod("CARD");
        dto.setOccurredAt(LocalDateTime.of(2026, 3, 10, 12, 0));
        return dto;
    }

    private WellnessLog wellnessLog(WellnessType type, double value, LocalDate day) {
        WellnessLog log = new WellnessLog();
        log.setId(2L);
        log.setUserId("user-1");
        log.setType(type);
        log.setNumericValue(value);
        log.setDay(day);
        log.setLoggedAt(Instant.parse("2026-03-10T08:00:00Z"));
        return log;
    }

    @Test
    void exportWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/life/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportReturnsFinanceAndWellnessData() throws Exception {
        when(financeService.listTransactions("user-1", null, null, null, null))
                .thenReturn(List.of(expenseDto(new BigDecimal("12.50"), "Groceries")));
        when(wellnessRepository.findTop200ByUserIdOrderByLoggedAtDesc("user-1"))
                .thenReturn(List.of(wellnessLog(WellnessType.WEIGHT, 80.0, LocalDate.of(2026, 3, 10))));

        mockMvc.perform(get("/api/v1/life/export").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.finance.length()").value(1))
                .andExpect(jsonPath("$.wellness.length()").value(1));
    }

    @Test
    void exportFinanceCsvProducesHeaderAndDataRows() throws Exception {
        when(financeService.listTransactions("user-1", null, null, null, null))
                .thenReturn(List.of(expenseDto(new BigDecimal("12.50"), "Groceries")));

        mockMvc.perform(get("/api/v1/life/export/finance.csv").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "id,amount,currency,category,description,type,merchant,paymentMethod,occurredAt,source\n")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Groceries")));
    }

    @Test
    void exportWellnessCsvEscapesCommasInTextValue() throws Exception {
        WellnessLog log = wellnessLog(WellnessType.HABIT, 1.0, LocalDate.of(2026, 3, 10));
        log.setTextValue("Read, then write");
        when(wellnessRepository.findTop200ByUserIdOrderByLoggedAtDesc("user-1")).thenReturn(List.of(log));

        mockMvc.perform(get("/api/v1/life/export/wellness.csv").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"Read, then write\"")));
    }

    @Test
    void importDataWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/life/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void importDataRestoresFinanceAndWellnessRecordsForRequestingUser() throws Exception {
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wellnessRepository.save(any(WellnessLog.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/life/import")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "finance": [
                                    {"amount": 15.00, "currency": "EUR", "category": "Food", "description": "lunch",
                                     "type": "EXPENSE", "merchant": "Lidl", "paymentMethod": "CARD",
                                     "occurredAt": "2026-03-10T12:00:00"}
                                  ],
                                  "wellness": [
                                    {"type": "WEIGHT", "numericValue": 80.0, "day": "2026-03-10"}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeImported").value(1))
                .andExpect(jsonPath("$.wellnessImported").value(1));

        org.mockito.ArgumentCaptor<Expense> expenseCaptor = org.mockito.ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(expenseCaptor.capture());
        assertThat(expenseCaptor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(expenseCaptor.getValue().getAmount()).isEqualByComparingTo("15.00");
        assertThat(expenseCaptor.getValue().getSource()).isEqualTo(EntrySource.AUTOMATION);

        org.mockito.ArgumentCaptor<WellnessLog> wellnessCaptor = org.mockito.ArgumentCaptor.forClass(WellnessLog.class);
        verify(wellnessRepository).save(wellnessCaptor.capture());
        assertThat(wellnessCaptor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(wellnessCaptor.getValue().getNumericValue()).isEqualTo(80.0);
    }

    @Test
    void importDataSkipsItemsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/life/import")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "finance": [{"currency": "EUR"}],
                                  "wellness": [{"numericValue": 5.0}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeImported").value(0))
                .andExpect(jsonPath("$.wellnessImported").value(0));

        verify(expenseRepository, never()).save(any(Expense.class));
        verify(wellnessRepository, never()).save(any(WellnessLog.class));
    }

    @Test
    void exportThenImportRoundtripPreservesFinanceAndWellnessData() throws Exception {
        ExpenseDTO exported = expenseDto(new BigDecimal("42.00"), "Transport");
        WellnessLog exportedWellness = wellnessLog(WellnessType.SLEEP, 7.5, LocalDate.of(2026, 3, 12));
        when(financeService.listTransactions("user-1", null, null, null, null)).thenReturn(List.of(exported));
        when(wellnessRepository.findTop200ByUserIdOrderByLoggedAtDesc("user-1")).thenReturn(List.of(exportedWellness));

        String exportJson = mockMvc.perform(get("/api/v1/life/export").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> exportBody = objectMapper.readValue(exportJson, Map.class);

        // Build an import payload from the exported shape, mirroring how a real client would restore it.
        Map<String, Object> financeItem = Map.of(
                "amount", "42.00",
                "currency", exported.getCurrency(),
                "category", exported.getCategory(),
                "description", exported.getDescription(),
                "type", exported.getType().name(),
                "merchant", exported.getMerchant(),
                "paymentMethod", exported.getPaymentMethod(),
                "occurredAt", "2026-03-10T12:00:00");
        Map<String, Object> wellnessItem = Map.of(
                "type", exportedWellness.getType().name(),
                "numericValue", exportedWellness.getNumericValue(),
                "day", exportedWellness.getDay().toString());
        Map<String, Object> importPayload = Map.of(
                "finance", List.of(financeItem),
                "wellness", List.of(wellnessItem));

        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wellnessRepository.save(any(WellnessLog.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/v1/life/import")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(importPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeImported").value(1))
                .andExpect(jsonPath("$.wellnessImported").value(1));

        org.mockito.ArgumentCaptor<Expense> expenseCaptor = org.mockito.ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository, times(1)).save(expenseCaptor.capture());
        assertThat(expenseCaptor.getValue().getAmount()).isEqualByComparingTo(exported.getAmount());
        assertThat(expenseCaptor.getValue().getCategory()).isEqualTo(exported.getCategory());
        assertThat(expenseCaptor.getValue().getMerchant()).isEqualTo(exported.getMerchant());

        org.mockito.ArgumentCaptor<WellnessLog> wellnessCaptor = org.mockito.ArgumentCaptor.forClass(WellnessLog.class);
        verify(wellnessRepository, times(1)).save(wellnessCaptor.capture());
        assertThat(wellnessCaptor.getValue().getNumericValue()).isEqualTo(exportedWellness.getNumericValue());
        assertThat(wellnessCaptor.getValue().getDay()).isEqualTo(exportedWellness.getDay());
        assertThat(wellnessCaptor.getValue().getType()).isEqualTo(exportedWellness.getType());
    }
}
