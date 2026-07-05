package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.domain.WellnessLog;
import org.jarvis.lifetracker.domain.WellnessType;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.jarvis.lifetracker.service.FinanceService;
import org.jarvis.lifetracker.util.CsvUtils;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exports/imports the user's own life-tracker data (data ownership / GDPR-style takeout + restore). */
@Slf4j
@RestController
@RequestMapping("/api/v1/life")
@RequiredArgsConstructor
public class ExportController {

    private final WellnessLogRepository wellnessRepository;
    private final ExpenseRepository expenseRepository;
    private final FinanceService financeService;

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(HttpServletRequest http) {
        String userId = requireUserId(http);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("exportedAt", Instant.now().toString());
        out.put("finance", financeService.listTransactions(userId, null, null, null, null));
        out.put("wellness", wellnessRepository.findTop200ByUserIdOrderByLoggedAtDesc(userId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jarvis-life-export.json\"")
                .body(out);
    }

    @GetMapping(value = "/export/finance.csv")
    public ResponseEntity<String> exportFinanceCsv(HttpServletRequest http) {
        String userId = requireUserId(http);
        List<ExpenseDTO> transactions = financeService.listTransactions(userId, null, null, null, null);
        StringBuilder csv = new StringBuilder(
                "id,amount,currency,category,description,type,merchant,paymentMethod,occurredAt,source\n");
        for (ExpenseDTO t : transactions) {
            appendCsvRow(csv, t.getId(), t.getAmount(), t.getCurrency(), t.getCategory(), t.getDescription(),
                    t.getType(), t.getMerchant(), t.getPaymentMethod(), t.getOccurredAt(), t.getSource());
        }
        return csvResponse(csv.toString(), "jarvis-finance-export.csv");
    }

    @GetMapping(value = "/export/wellness.csv")
    public ResponseEntity<String> exportWellnessCsv(HttpServletRequest http) {
        String userId = requireUserId(http);
        List<WellnessLog> logs = wellnessRepository.findTop200ByUserIdOrderByLoggedAtDesc(userId);
        StringBuilder csv = new StringBuilder("id,type,numericValue,textValue,loggedAt,day\n");
        for (WellnessLog entry : logs) {
            appendCsvRow(csv, entry.getId(), entry.getType(), entry.getNumericValue(), entry.getTextValue(),
                    entry.getLoggedAt(), entry.getDay());
        }
        return csvResponse(csv.toString(), "jarvis-wellness-export.csv");
    }

    /** One row per exported finance transaction, used to restore data via {@link #importData}. */
    public record FinanceImportItem(
            BigDecimal amount,
            String currency,
            String category,
            String description,
            TransactionType type,
            String merchant,
            String paymentMethod,
            LocalDateTime occurredAt) {
    }

    /** One row per exported wellness entry, used to restore data via {@link #importData}. */
    public record WellnessImportItem(
            WellnessType type,
            Double numericValue,
            String textValue,
            LocalDate day,
            Instant loggedAt) {
    }

    public record ImportRequest(List<FinanceImportItem> finance, List<WellnessImportItem> wellness) {
    }

    public record ImportResultDTO(int financeImported, int wellnessImported) {
    }

    /**
     * JSON import/restore: re-creates finance + wellness records for the requesting user from a
     * payload shaped like {@link #export}'s output. The caller's own {@code X-User-Id} is always
     * used as the owner — a restored payload can never write data into another user's account.
     */
    @PostMapping("/import")
    @Transactional
    public ImportResultDTO importData(@RequestBody ImportRequest request, HttpServletRequest http) {
        String userId = requireUserId(http);

        int financeImported = 0;
        if (request != null && request.finance() != null) {
            for (FinanceImportItem item : request.finance()) {
                if (item == null || item.amount() == null) {
                    continue;
                }
                Expense expense = new Expense();
                expense.setUserId(userId);
                expense.setAmount(item.amount());
                expense.setCurrency(item.currency() != null ? item.currency() : "EUR");
                expense.setCategory(item.category());
                expense.setDescription(item.description());
                expense.setType(item.type() != null ? item.type() : TransactionType.EXPENSE);
                expense.setMerchant(item.merchant());
                expense.setPaymentMethod(item.paymentMethod());
                expense.setOccurredAt(item.occurredAt() != null ? item.occurredAt() : LocalDateTime.now());
                expense.setSource(EntrySource.AUTOMATION);
                expenseRepository.save(expense);
                financeImported++;
            }
        }

        int wellnessImported = 0;
        if (request != null && request.wellness() != null) {
            for (WellnessImportItem item : request.wellness()) {
                if (item == null || item.type() == null) {
                    continue;
                }
                WellnessLog entry = new WellnessLog();
                entry.setUserId(userId);
                entry.setType(item.type());
                entry.setNumericValue(item.numericValue());
                entry.setTextValue(item.textValue());
                entry.setDay(item.day() != null ? item.day() : LocalDate.now());
                entry.setLoggedAt(item.loggedAt() != null ? item.loggedAt() : Instant.now());
                wellnessRepository.save(entry);
                wellnessImported++;
            }
        }

        log.info("Restored {} finance + {} wellness records for {}", financeImported, wellnessImported, userId);
        return new ImportResultDTO(financeImported, wellnessImported);
    }

    private static void appendCsvRow(StringBuilder csv, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(CsvUtils.escape(values[i]));
        }
        csv.append('\n');
    }

    private static ResponseEntity<String> csvResponse(String body, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
