package org.jarvis.lifetracker.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseDTO {
    private Long id;
    private String userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount; // Changed from Double

    private String currency;
    private String category;
    private String description;
    private TransactionType type;
    private String merchant;
    private String paymentMethod;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime occurredAt;

    private EntrySource source;
    private Instant createdAt;
    private Instant updatedAt;
}
