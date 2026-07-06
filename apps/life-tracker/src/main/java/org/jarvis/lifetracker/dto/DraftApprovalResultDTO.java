package org.jarvis.lifetracker.dto;

/**
 * Result of approving a review-inbox draft: either a newly created {@link ExpenseDTO} or, when
 * duplicate detection (US-BANK-006) finds a matching dedup key already stored, the existing
 * expense with {@code duplicate=true} instead of a second row.
 */
public record DraftApprovalResultDTO(ExpenseDTO expense, boolean duplicate) {

    public static DraftApprovalResultDTO created(ExpenseDTO expense) {
        return new DraftApprovalResultDTO(expense, false);
    }

    public static DraftApprovalResultDTO duplicate(ExpenseDTO expense) {
        return new DraftApprovalResultDTO(expense, true);
    }
}
