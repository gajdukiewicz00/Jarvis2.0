package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.dto.ParsedTransactionDTO;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.jarvis.lifetracker.service.BankNotificationParser;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
            Expense saved = expenseRepository.save(e);
            log.info("stored parsed bank tx id={} amount={} {} cat={} dedup={}",
                    saved.getId(), d.amount(), d.currency(), d.category(), d.dedupKey());
            return new ParsedTransactionDTO(d.valid(), d.confidence(), d.needsReview(), d.amount(), d.currency(),
                    d.merchant(), d.type(), d.category(), d.cardMask(), d.dedupKey(), d.occurredAt(),
                    d.rawMasked(), d.notes(), saved.getId());
        }
        return d;
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
