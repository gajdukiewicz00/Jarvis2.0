package org.jarvis.lifetracker.dto;

import java.math.BigDecimal;

/** Partial-update request body for editing a review-inbox draft before approval. Null = leave unchanged. */
public record DraftEditRequestDTO(BigDecimal amount, String merchant, String category, String currency) {
}
