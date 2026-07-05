package org.jarvis.lifetracker.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "expense",
        uniqueConstraints = @UniqueConstraint(name = "uq_expense_user_dedup", columnNames = {"user_id", "dedup_key"}))
@Data
public class Expense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(length = 10)
    private String currency;

    @Column(length = 100)
    private String category;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TransactionType type = TransactionType.EXPENSE;

    @Column(length = 255)
    private String merchant;

    @Column(name = "payment_method", length = 100)
    private String paymentMethod;

    @Column(name = "date", nullable = false)
    private LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private EntrySource source = EntrySource.MANUAL;

    /**
     * Deterministic fingerprint (see {@code BankNotificationParser}) used to prevent the same
     * bank-notification draft from being imported/stored twice. Null for manually entered
     * transactions; a standard SQL UNIQUE constraint treats NULLs as distinct so those rows never
     * collide with one another.
     */
    @Column(name = "dedup_key", length = 64)
    private String dedupKey;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
