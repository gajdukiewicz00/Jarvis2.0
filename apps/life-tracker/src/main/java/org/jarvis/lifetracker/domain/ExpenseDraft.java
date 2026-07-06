package org.jarvis.lifetracker.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A MEDIUM/LOW-confidence (or invalid) bank-notification parse that was NOT auto-stored as an
 * {@link Expense} (US-BANK-005). It sits in the manual review inbox until the user approves,
 * edits-then-approves, or rejects it (FINANCE-REVIEW).
 *
 * <p>{@code dedupKey} is copied verbatim from the original {@code ParsedTransactionDTO} so
 * approval can reuse the same fingerprint-based duplicate detection as the HIGH-confidence
 * auto-store path, even if the user edits amount/merchant/category before approving.
 */
@Entity
@Table(name = "expense_draft")
@Data
public class ExpenseDraft {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    /** Nullable: LOW-confidence parses may be missing an amount until the user edits it in. */
    private BigDecimal amount;

    @Column(length = 10)
    private String currency;

    @Column(length = 100)
    private String category;

    @Column(length = 255)
    private String merchant;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TransactionType type = TransactionType.EXPENSE;

    @Column(name = "payment_method", length = 100)
    private String paymentMethod;

    @Column(name = "date")
    private LocalDateTime occurredAt;

    /** HIGH | MEDIUM | LOW, as scored by {@code BankNotificationParser}. */
    @Column(length = 20)
    private String confidence;

    @Column(name = "dedup_key", length = 64)
    private String dedupKey;

    @Column(name = "raw_masked", length = 1000)
    private String rawMasked;

    /** Parser validation/parsing notes, joined with "; " for storage. */
    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private DraftStatus status = DraftStatus.DRAFT;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
