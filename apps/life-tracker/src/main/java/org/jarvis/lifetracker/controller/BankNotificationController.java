package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.dto.ParsedTransactionDTO;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.service.BankNotificationParser;
import org.jarvis.lifetracker.util.CsvUtils;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bank push-notification → transaction draft (Banking Push Parser epic).
 *
 * <p>Deterministic + local-only (no external LLM). Returns a parsed draft with a
 * confidence score; with {@code store=true} it persists ONLY HIGH-confidence valid
 * drafts (US-BANK-005: low/medium stay in the manual inbox). Card details are masked
 * (US-BANK-009) and the raw text is never stored verbatim.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/life/finance")
@RequiredArgsConstructor
public class BankNotificationController {

    private final BankNotificationParser parser;
    private final ExpenseRepository expenseRepository;

    public record ParseRequest(String text, Boolean store) {}

    @PostMapping("/parse-notification")
    public ParsedTransactionDTO parseNotification(@RequestBody ParseRequest req, HttpServletRequest http) {
        if (req == null || req.text() == null || req.text().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text_required");
        }
        ParsedTransactionDTO d = parser.parse(req.text());

        if (Boolean.TRUE.equals(req.store()) && d.valid() && "HIGH".equals(d.confidence()) && !d.needsReview()) {
            String userId = requireUserId(http);

            Optional<Expense> duplicate = expenseRepository.findByUserIdAndDedupKey(userId, d.dedupKey());
            if (duplicate.isPresent()) {
                log.info("skipped duplicate bank tx dedup={} already stored as id={}",
                        d.dedupKey(), duplicate.get().getId());
                return withNoteAndStoredId(d, "duplicate_skipped", duplicate.get().getId());
            }

            try {
                Expense saved = expenseRepository.save(buildExpense(d, userId));
                log.info("stored parsed bank tx id={} amount={} {} cat={} dedup={}",
                        saved.getId(), d.amount(), d.currency(), d.category(), d.dedupKey());
                return withNoteAndStoredId(d, null, saved.getId());
            } catch (DataIntegrityViolationException ex) {
                // Race: another request stored the same dedup key between our check and insert.
                Expense existing = expenseRepository.findByUserIdAndDedupKey(userId, d.dedupKey()).orElseThrow(() -> ex);
                log.warn("concurrent duplicate bank tx dedup={} resolved to existing id={}",
                        d.dedupKey(), existing.getId());
                return withNoteAndStoredId(d, "duplicate_skipped", existing.getId());
            }
        }
        return d;
    }

    private Expense buildExpense(ParsedTransactionDTO d, String userId) {
        Expense e = new Expense();
        e.setUserId(userId);
        e.setAmount(d.amount());
        e.setCurrency(d.currency());
        e.setCategory(d.category());
        e.setMerchant(d.merchant());
        e.setType(d.type());
        e.setPaymentMethod(d.cardMask());
        e.setDescription("bank: " + (d.merchant() == null ? "transaction" : d.merchant()));
        e.setOccurredAt(d.occurredAt());
        e.setSource(EntrySource.AI);
        e.setDedupKey(d.dedupKey());
        return e;
    }

    private ParsedTransactionDTO withNoteAndStoredId(ParsedTransactionDTO d, String extraNote, Long storedId) {
        List<String> notes = new ArrayList<>(d.notes());
        if (extraNote != null) {
            notes.add(extraNote);
        }
        return new ParsedTransactionDTO(d.valid(), d.confidence(), d.needsReview(), d.amount(), d.currency(),
                d.merchant(), d.type(), d.category(), d.cardMask(), d.dedupKey(), d.occurredAt(),
                d.rawMasked(), notes, storedId);
    }

    /**
     * Bulk CSV import of raw bank notification texts (one notification per row/field, CSV-quoted
     * so embedded commas / decimal commas survive). Each row is run through the same deterministic
     * {@link BankNotificationParser}; HIGH-confidence valid drafts are auto-stored, everything else
     * (LOW/MEDIUM confidence, or invalid) is returned in {@code needsReview} for a manual inbox
     * instead of being silently discarded (US-BANK-005).
     */
    @PostMapping("/import-csv-notifications")
    public Map<String, Object> importCsvNotifications(@RequestBody Map<String, String> body, HttpServletRequest http) {
        String userId = requireUserId(http);
        String csv = body == null ? "" : body.getOrDefault("csv", "");
        List<List<String>> rows = CsvUtils.parseCsv(csv);

        int imported = 0;
        int duplicatesSkipped = 0;
        List<ParsedTransactionDTO> needsReview = new ArrayList<>();
        for (List<String> row : rows) {
            if (row.isEmpty()) {
                continue;
            }
            String text = row.get(0);
            if (text == null || text.isBlank() || "text".equalsIgnoreCase(text.trim())) {
                continue; // blank row / header row
            }
            ParsedTransactionDTO parsed = parser.parse(text);
            if (parsed.valid() && "HIGH".equals(parsed.confidence()) && !parsed.needsReview()) {
                if (expenseRepository.existsByUserIdAndDedupKey(userId, parsed.dedupKey())) {
                    duplicatesSkipped++;
                    continue;
                }
                try {
                    expenseRepository.save(buildExpense(parsed, userId));
                    imported++;
                } catch (DataIntegrityViolationException ex) {
                    duplicatesSkipped++;
                }
            } else {
                needsReview.add(parsed);
            }
        }
        log.info("CSV bank import for {}: imported={} needsReview={} duplicatesSkipped={}",
                userId, imported, needsReview.size(), duplicatesSkipped);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("needsReview", needsReview);
        result.put("totalRows", rows.size());
        result.put("duplicatesSkipped", duplicatesSkipped);
        return result;
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
