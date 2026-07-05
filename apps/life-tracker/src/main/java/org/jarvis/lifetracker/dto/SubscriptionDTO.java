package org.jarvis.lifetracker.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A recurring-charge pattern detected in a user's transaction history: same merchant,
 * roughly the same amount, roughly monthly cadence. See
 * {@code org.jarvis.lifetracker.service.SubscriptionDetectionService}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionDTO {
    private String merchant;
    private String category;
    private BigDecimal averageAmount;
    private String currency;
    private int occurrences;
    private long averageIntervalDays;
    private LocalDateTime firstChargedAt;
    private LocalDateTime lastChargedAt;
    private LocalDateTime nextExpectedChargeAt;
}
