package org.jarvis.lifetracker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.domain.DraftStatus;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.ExpenseDraft;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.DraftApprovalResultDTO;
import org.jarvis.lifetracker.dto.DraftEditRequestDTO;
import org.jarvis.lifetracker.dto.ExpenseDraftDTO;
import org.jarvis.lifetracker.dto.ReviewInboxPageDTO;
import org.jarvis.lifetracker.dto.ParsedTransactionDTO;
import org.jarvis.lifetracker.repository.ExpenseDraftRepository;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * FINANCE-REVIEW: the manual review inbox for MEDIUM/LOW-confidence (or invalid) bank-notification
 * parses. Instead of being dropped, {@code BankNotificationController} persists them here
 * (status=DRAFT); this service lists, edits, approves (-> real {@link Expense}, reusing the
 * wave-4 dedup key/index) and rejects them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewInboxService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ExpenseDraftRepository draftRepository;
    private final ExpenseRepository expenseRepository;
    private final DTOMapper dtoMapper;

    /** Persists a parsed notification that needs manual review instead of dropping it (US-BANK-005). */
    public ExpenseDraft createDraft(String userId, ParsedTransactionDTO parsed) {
        ExpenseDraft draft = new ExpenseDraft();
        draft.setUserId(userId);
        draft.setAmount(parsed.amount());
        draft.setCurrency(parsed.currency());
        draft.setCategory(parsed.category());
        draft.setMerchant(parsed.merchant());
        draft.setType(parsed.type() != null ? parsed.type() : TransactionType.EXPENSE);
        draft.setPaymentMethod(parsed.cardMask());
        draft.setOccurredAt(parsed.occurredAt() != null ? parsed.occurredAt() : LocalDateTime.now());
        draft.setConfidence(parsed.confidence());
        draft.setDedupKey(parsed.dedupKey());
        draft.setRawMasked(parsed.rawMasked());
        draft.setNotes(parsed.notes() == null || parsed.notes().isEmpty() ? null : String.join("; ", parsed.notes()));
        draft.setStatus(DraftStatus.DRAFT);
        return draftRepository.save(draft);
    }

    public ReviewInboxPageDTO listInbox(String userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size <= 0 ? DEFAULT_PAGE_SIZE : size, 1), MAX_PAGE_SIZE);
        Page<ExpenseDraft> result = draftRepository.findByUserIdAndStatus(userId, DraftStatus.DRAFT,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<ExpenseDraftDTO> items = result.getContent().stream().map(dtoMapper::toDTO).toList();
        return new ReviewInboxPageDTO(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /** Edits amount/merchant/category/currency on a still-pending draft. Null fields are left unchanged. */
    public ExpenseDraftDTO editDraft(String userId, Long draftId, DraftEditRequestDTO edit) {
        ExpenseDraft draft = requirePendingDraft(userId, draftId);
        if (edit != null) {
            if (edit.amount() != null) {
                draft.setAmount(edit.amount());
            }
            if (edit.merchant() != null) {
                draft.setMerchant(edit.merchant());
            }
            if (edit.category() != null) {
                draft.setCategory(edit.category());
            }
            if (edit.currency() != null) {
                draft.setCurrency(edit.currency());
            }
        }
        return dtoMapper.toDTO(draftRepository.save(draft));
    }

    /**
     * Approves a draft: validates it is complete, then persists it as a real {@link Expense},
     * reusing the same dedup-key duplicate detection as the HIGH-confidence auto-store path
     * (US-BANK-006). If a matching expense already exists, the draft is still marked APPROVED but
     * no second expense row is created; the existing one is returned with {@code duplicate=true}.
     */
    public DraftApprovalResultDTO approveDraft(String userId, Long draftId) {
        ExpenseDraft draft = requirePendingDraft(userId, draftId);
        if (draft.getAmount() == null || draft.getCurrency() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "draft_incomplete: amount and currency are required before approval");
        }

        String dedupKey = draft.getDedupKey();
        Optional<Expense> duplicate = dedupKey == null
                ? Optional.empty()
                : expenseRepository.findByUserIdAndDedupKey(userId, dedupKey);
        if (duplicate.isPresent()) {
            markApproved(draft);
            log.info("draft id={} approved as duplicate of existing expense id={}", draftId, duplicate.get().getId());
            return DraftApprovalResultDTO.duplicate(dtoMapper.toDTO(duplicate.get()));
        }

        try {
            Expense saved = expenseRepository.save(buildExpense(draft));
            markApproved(draft);
            log.info("draft id={} approved -> expense id={}", draftId, saved.getId());
            return DraftApprovalResultDTO.created(dtoMapper.toDTO(saved));
        } catch (DataIntegrityViolationException ex) {
            // Race: another request stored the same dedup key between our check and insert.
            Expense existing = dedupKey == null ? null
                    : expenseRepository.findByUserIdAndDedupKey(userId, dedupKey).orElse(null);
            if (existing == null) {
                throw ex;
            }
            markApproved(draft);
            log.warn("draft id={} concurrent duplicate resolved to existing expense id={}", draftId, existing.getId());
            return DraftApprovalResultDTO.duplicate(dtoMapper.toDTO(existing));
        }
    }

    /** Discards a draft; it disappears from the review inbox listing. */
    public void rejectDraft(String userId, Long draftId) {
        ExpenseDraft draft = requirePendingDraft(userId, draftId);
        draft.setStatus(DraftStatus.REJECTED);
        draftRepository.save(draft);
    }

    private void markApproved(ExpenseDraft draft) {
        draft.setStatus(DraftStatus.APPROVED);
        draftRepository.save(draft);
    }

    private ExpenseDraft requirePendingDraft(String userId, Long draftId) {
        return draftRepository.findByIdAndUserId(draftId, userId)
                .filter(draft -> draft.getStatus() == DraftStatus.DRAFT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "draft_not_found"));
    }

    private Expense buildExpense(ExpenseDraft draft) {
        Expense expense = new Expense();
        expense.setUserId(draft.getUserId());
        expense.setAmount(draft.getAmount());
        expense.setCurrency(draft.getCurrency());
        expense.setCategory(draft.getCategory());
        expense.setMerchant(draft.getMerchant());
        expense.setType(draft.getType() != null ? draft.getType() : TransactionType.EXPENSE);
        expense.setPaymentMethod(draft.getPaymentMethod());
        expense.setDescription("bank: " + (draft.getMerchant() == null ? "transaction" : draft.getMerchant()));
        expense.setOccurredAt(draft.getOccurredAt() != null ? draft.getOccurredAt() : LocalDateTime.now());
        expense.setSource(EntrySource.AI);
        expense.setDedupKey(draft.getDedupKey());
        return expense;
    }
}
