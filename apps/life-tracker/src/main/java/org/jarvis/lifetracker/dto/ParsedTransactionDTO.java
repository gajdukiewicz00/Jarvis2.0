package org.jarvis.lifetracker.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.jarvis.lifetracker.domain.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of parsing a raw bank push notification into a transaction draft.
 * Deterministic + local-only (no external LLM) so it cannot hallucinate finances.
 *
 * <p>US-BANK-002 (extract), 003 (validate), 004 (confidence), 005 (needs-review inbox),
 * 006 (dedupKey), 007 (category), 009 (cardMask), 010 (local-only).
 */
public record ParsedTransactionDTO(
        boolean valid,
        String confidence,        // HIGH | MEDIUM | LOW
        boolean needsReview,      // true => goes to manual inbox, NOT auto-stored

        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal amount,
        String currency,
        String merchant,
        TransactionType type,
        String category,
        String cardMask,          // e.g. "**** 1234" — raw PAN is never kept

        String dedupKey,
        LocalDateTime occurredAt,
        String rawMasked,         // original text with card digits masked
        List<String> notes,       // validation / parsing notes
        Long storedId             // non-null only if it was persisted
) {}
