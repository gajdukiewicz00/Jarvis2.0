package org.jarvis.lifetracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.lifetracker.domain.RecurringInterval;
import org.jarvis.lifetracker.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecurringTransactionDTO {
    private Long id;
    private String userId;
    private BigDecimal amount;
    private String currency;
    private String category;
    private String description;
    private TransactionType type;
    private String merchant;
    private RecurringInterval interval;
    private LocalDateTime nextRun;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
