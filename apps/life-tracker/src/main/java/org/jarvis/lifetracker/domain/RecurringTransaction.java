package org.jarvis.lifetracker.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "recurring_transaction")
@Data
public class RecurringTransaction {
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

    @Enumerated(EnumType.STRING)
    @Column(name = "recurrence_interval", length = 20)
    private RecurringInterval interval = RecurringInterval.MONTHLY;

    @Column(name = "next_run")
    private LocalDateTime nextRun;

    @Column(name = "active")
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
