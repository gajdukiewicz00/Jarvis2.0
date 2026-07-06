package org.jarvis.lifetracker.dto;

import java.util.List;

/**
 * Paginated envelope for the review-inbox listing endpoint, following the project convention of
 * including page metadata (total, page, limit) rather than exposing Spring's {@code Page} type.
 */
public record ReviewInboxPageDTO(
        List<ExpenseDraftDTO> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
