package org.jarvis.lifetracker.service;

import org.jarvis.lifetracker.domain.DraftStatus;
import org.jarvis.lifetracker.domain.EntrySource;
import org.jarvis.lifetracker.domain.Expense;
import org.jarvis.lifetracker.domain.ExpenseDraft;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.DraftApprovalResultDTO;
import org.jarvis.lifetracker.dto.DraftEditRequestDTO;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.dto.ExpenseDraftDTO;
import org.jarvis.lifetracker.dto.ParsedTransactionDTO;
import org.jarvis.lifetracker.dto.ReviewInboxPageDTO;
import org.jarvis.lifetracker.repository.ExpenseDraftRepository;
import org.jarvis.lifetracker.repository.ExpenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewInboxServiceTest {

    private static final String USER_ID = "user-1";

    @Mock
    private ExpenseDraftRepository draftRepository;
    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private DTOMapper dtoMapper;

    private ReviewInboxService reviewInboxService;

    @BeforeEach
    void setUp() {
        reviewInboxService = new ReviewInboxService(draftRepository, expenseRepository, dtoMapper);
    }

    private ParsedTransactionDTO lowConfidenceParse() {
        return new ParsedTransactionDTO(false, "LOW", true, null, null, null,
                TransactionType.EXPENSE, "uncategorized", null, "dedup-abc", LocalDateTime.now(),
                "masked", List.of("no amount found", "currency not recognised"), null, null);
    }

    private ParsedTransactionDTO mediumConfidenceParse() {
        return new ParsedTransactionDTO(true, "MEDIUM", true, new BigDecimal("20.50"), "USD", null,
                TransactionType.EXPENSE, "uncategorized", null, "dedup-xyz", LocalDateTime.now(),
                "masked", List.of(), null, null);
    }

    private ExpenseDraft draft(Long id, DraftStatus status) {
        ExpenseDraft draft = new ExpenseDraft();
        draft.setId(id);
        draft.setUserId(USER_ID);
        draft.setAmount(new BigDecimal("20.50"));
        draft.setCurrency("USD");
        draft.setCategory("uncategorized");
        draft.setMerchant("Old Merchant");
        draft.setType(TransactionType.EXPENSE);
        draft.setDedupKey("dedup-xyz");
        draft.setStatus(status);
        return draft;
    }

    // ---- createDraft ----

    @Test
    void createDraftPersistsLowConfidenceParseWithJoinedNotes() {
        ParsedTransactionDTO parsed = lowConfidenceParse();
        when(draftRepository.save(any(ExpenseDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseDraft saved = reviewInboxService.createDraft(USER_ID, parsed);

        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getConfidence()).isEqualTo("LOW");
        assertThat(saved.getStatus()).isEqualTo(DraftStatus.DRAFT);
        assertThat(saved.getDedupKey()).isEqualTo("dedup-abc");
        assertThat(saved.getNotes()).isEqualTo("no amount found; currency not recognised");
        assertThat(saved.getAmount()).isNull();
    }

    @Test
    void createDraftStoresNullNotesWhenParserReportedNone() {
        ParsedTransactionDTO parsed = mediumConfidenceParse();
        when(draftRepository.save(any(ExpenseDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseDraft saved = reviewInboxService.createDraft(USER_ID, parsed);

        assertThat(saved.getNotes()).isNull();
        assertThat(saved.getAmount()).isEqualByComparingTo("20.50");
    }

    // ---- listInbox ----

    @Test
    void listInboxMapsDraftPageToDtoPageMetadata() {
        ExpenseDraft entity = draft(1L, DraftStatus.DRAFT);
        ExpenseDraftDTO dto = new ExpenseDraftDTO();
        dto.setId(1L);
        Page<ExpenseDraft> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1);
        when(draftRepository.findByUserIdAndStatus(eq(USER_ID), eq(DraftStatus.DRAFT), any(Pageable.class)))
                .thenReturn(page);
        when(dtoMapper.toDTO(entity)).thenReturn(dto);

        ReviewInboxPageDTO result = reviewInboxService.listInbox(USER_ID, 0, 20);

        assertThat(result.items()).containsExactly(dto);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
    }

    @Test
    void listInboxClampsOversizedPageSize() {
        Page<ExpenseDraft> page = new PageImpl<>(List.of());
        when(draftRepository.findByUserIdAndStatus(eq(USER_ID), eq(DraftStatus.DRAFT), any(Pageable.class)))
                .thenReturn(page);

        reviewInboxService.listInbox(USER_ID, 0, 10_000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(draftRepository).findByUserIdAndStatus(eq(USER_ID), eq(DraftStatus.DRAFT), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    // ---- editDraft ----

    @Test
    void editDraftUpdatesOnlyProvidedFields() {
        ExpenseDraft existing = draft(5L, DraftStatus.DRAFT);
        when(draftRepository.findByIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(existing));
        when(draftRepository.save(any(ExpenseDraft.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dtoMapper.toDTO(any(ExpenseDraft.class))).thenReturn(new ExpenseDraftDTO());

        reviewInboxService.editDraft(USER_ID, 5L,
                new DraftEditRequestDTO(new BigDecimal("30.00"), "New Merchant", null, null));

        ArgumentCaptor<ExpenseDraft> captor = ArgumentCaptor.forClass(ExpenseDraft.class);
        verify(draftRepository).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("30.00");
        assertThat(captor.getValue().getMerchant()).isEqualTo("New Merchant");
        assertThat(captor.getValue().getCategory()).isEqualTo("uncategorized"); // unchanged
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD"); // unchanged
    }

    @Test
    void editDraftThrowsNotFoundWhenDraftBelongsToAnotherUserOrMissing() {
        when(draftRepository.findByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewInboxService.editDraft(USER_ID, 99L,
                new DraftEditRequestDTO(BigDecimal.TEN, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("draft_not_found");

        verify(draftRepository, never()).save(any(ExpenseDraft.class));
    }

    @Test
    void editDraftThrowsNotFoundWhenAlreadyProcessed() {
        ExpenseDraft approved = draft(6L, DraftStatus.APPROVED);
        when(draftRepository.findByIdAndUserId(6L, USER_ID)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> reviewInboxService.editDraft(USER_ID, 6L,
                new DraftEditRequestDTO(BigDecimal.TEN, null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ---- approveDraft ----

    @Test
    void approveDraftCreatesExpenseAndMarksDraftApprovedWhenNoDuplicateExists() {
        ExpenseDraft existing = draft(10L, DraftStatus.DRAFT);
        when(draftRepository.findByIdAndUserId(10L, USER_ID)).thenReturn(Optional.of(existing));
        when(expenseRepository.findByUserIdAndDedupKey(USER_ID, "dedup-xyz")).thenReturn(Optional.empty());
        when(expenseRepository.save(any(Expense.class))).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            e.setId(500L);
            return e;
        });
        ExpenseDTO expenseDto = new ExpenseDTO();
        expenseDto.setId(500L);
        when(dtoMapper.toDTO(any(Expense.class))).thenReturn(expenseDto);

        DraftApprovalResultDTO result = reviewInboxService.approveDraft(USER_ID, 10L);

        assertThat(result.duplicate()).isFalse();
        assertThat(result.expense().getId()).isEqualTo(500L);

        ArgumentCaptor<Expense> expenseCaptor = ArgumentCaptor.forClass(Expense.class);
        verify(expenseRepository).save(expenseCaptor.capture());
        assertThat(expenseCaptor.getValue().getSource()).isEqualTo(EntrySource.AI);
        assertThat(expenseCaptor.getValue().getDedupKey()).isEqualTo("dedup-xyz");

        ArgumentCaptor<ExpenseDraft> draftCaptor = ArgumentCaptor.forClass(ExpenseDraft.class);
        verify(draftRepository).save(draftCaptor.capture());
        assertThat(draftCaptor.getValue().getStatus()).isEqualTo(DraftStatus.APPROVED);
    }

    @Test
    void approveDraftReturnsExistingExpenseAsDuplicateWithoutCreatingASecondRow() {
        ExpenseDraft existing = draft(11L, DraftStatus.DRAFT);
        Expense duplicateExpense = new Expense();
        duplicateExpense.setId(42L);
        when(draftRepository.findByIdAndUserId(11L, USER_ID)).thenReturn(Optional.of(existing));
        when(expenseRepository.findByUserIdAndDedupKey(USER_ID, "dedup-xyz")).thenReturn(Optional.of(duplicateExpense));
        ExpenseDTO expenseDto = new ExpenseDTO();
        expenseDto.setId(42L);
        when(dtoMapper.toDTO(duplicateExpense)).thenReturn(expenseDto);

        DraftApprovalResultDTO result = reviewInboxService.approveDraft(USER_ID, 11L);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.expense().getId()).isEqualTo(42L);
        verify(expenseRepository, never()).save(any(Expense.class));

        ArgumentCaptor<ExpenseDraft> draftCaptor = ArgumentCaptor.forClass(ExpenseDraft.class);
        verify(draftRepository).save(draftCaptor.capture());
        assertThat(draftCaptor.getValue().getStatus()).isEqualTo(DraftStatus.APPROVED);
    }

    @Test
    void approveDraftResolvesConcurrentRaceToExistingExpense() {
        ExpenseDraft existing = draft(12L, DraftStatus.DRAFT);
        Expense concurrentExpense = new Expense();
        concurrentExpense.setId(77L);
        when(draftRepository.findByIdAndUserId(12L, USER_ID)).thenReturn(Optional.of(existing));
        when(expenseRepository.findByUserIdAndDedupKey(USER_ID, "dedup-xyz"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentExpense));
        when(expenseRepository.save(any(Expense.class))).thenThrow(new DataIntegrityViolationException("dup"));
        ExpenseDTO expenseDto = new ExpenseDTO();
        expenseDto.setId(77L);
        when(dtoMapper.toDTO(concurrentExpense)).thenReturn(expenseDto);

        DraftApprovalResultDTO result = reviewInboxService.approveDraft(USER_ID, 12L);

        assertThat(result.duplicate()).isTrue();
        assertThat(result.expense().getId()).isEqualTo(77L);
    }

    @Test
    void approveDraftRejectsIncompleteDraftMissingAmountOrCurrency() {
        ExpenseDraft incomplete = draft(13L, DraftStatus.DRAFT);
        incomplete.setAmount(null);
        when(draftRepository.findByIdAndUserId(13L, USER_ID)).thenReturn(Optional.of(incomplete));

        assertThatThrownBy(() -> reviewInboxService.approveDraft(USER_ID, 13L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("draft_incomplete");

        verify(expenseRepository, never()).save(any(Expense.class));
    }

    @Test
    void approveDraftThrowsNotFoundForAlreadyProcessedDraft() {
        ExpenseDraft rejected = draft(14L, DraftStatus.REJECTED);
        when(draftRepository.findByIdAndUserId(14L, USER_ID)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> reviewInboxService.approveDraft(USER_ID, 14L))
                .isInstanceOf(ResponseStatusException.class);
        verify(expenseRepository, never()).save(any(Expense.class));
    }

    // ---- rejectDraft ----

    @Test
    void rejectDraftMarksStatusRejectedSoItDisappearsFromTheDraftInboxQuery() {
        ExpenseDraft existing = draft(20L, DraftStatus.DRAFT);
        when(draftRepository.findByIdAndUserId(20L, USER_ID)).thenReturn(Optional.of(existing));
        when(draftRepository.save(any(ExpenseDraft.class))).thenAnswer(inv -> inv.getArgument(0));

        reviewInboxService.rejectDraft(USER_ID, 20L);

        ArgumentCaptor<ExpenseDraft> captor = ArgumentCaptor.forClass(ExpenseDraft.class);
        verify(draftRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DraftStatus.REJECTED);
        // listInbox only ever queries status=DRAFT, so a REJECTED row is excluded by construction.
        verify(draftRepository, never()).findByUserIdAndStatus(anyString(), eq(DraftStatus.REJECTED), any());
    }

    @Test
    void rejectDraftThrowsNotFoundWhenMissing() {
        when(draftRepository.findByIdAndUserId(21L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewInboxService.rejectDraft(USER_ID, 21L))
                .isInstanceOf(ResponseStatusException.class);
    }
}
