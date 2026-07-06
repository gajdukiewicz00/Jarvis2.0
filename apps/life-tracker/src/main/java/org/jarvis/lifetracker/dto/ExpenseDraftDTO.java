package org.jarvis.lifetracker.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.lifetracker.domain.DraftStatus;
import org.jarvis.lifetracker.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/** Review-inbox draft (FINANCE-REVIEW): a MEDIUM/LOW-confidence bank-notification parse awaiting approval. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseDraftDTO {
    private Long id;
    private String userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    private String currency;
    private String category;
    private String merchant;
    private TransactionType type;
    private String paymentMethod;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime occurredAt;

    private String confidence;
    private String notes;
    private DraftStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
