package org.jarvis.lifetracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jarvis.lifetracker.domain.DraftStatus;
import org.jarvis.lifetracker.domain.TransactionType;
import org.jarvis.lifetracker.dto.DraftApprovalResultDTO;
import org.jarvis.lifetracker.dto.DraftEditRequestDTO;
import org.jarvis.lifetracker.dto.ExpenseDTO;
import org.jarvis.lifetracker.dto.ExpenseDraftDTO;
import org.jarvis.lifetracker.dto.ReviewInboxPageDTO;
import org.jarvis.lifetracker.service.ReviewInboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReviewInboxController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReviewInboxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReviewInboxService reviewInboxService;

    private ExpenseDraftDTO draftDto(Long id) {
        ExpenseDraftDTO dto = new ExpenseDraftDTO();
        dto.setId(id);
        dto.setUserId("user-1");
        dto.setAmount(new BigDecimal("20.50"));
        dto.setCurrency("USD");
        dto.setType(TransactionType.EXPENSE);
        dto.setConfidence("MEDIUM");
        dto.setStatus(DraftStatus.DRAFT);
        return dto;
    }

    @Test
    void listInboxReturnsPagedDrafts() throws Exception {
        ReviewInboxPageDTO page = new ReviewInboxPageDTO(List.of(draftDto(1L)), 0, 20, 1, 1);
        when(reviewInboxService.listInbox(eq("user-1"), anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/api/v1/life/finance/review-inbox")
                        .header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listInboxWithoutUserIdReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/life/finance/review-inbox"))
                .andExpect(status().isUnauthorized());

        verify(reviewInboxService, never()).listInbox(any(), anyInt(), anyInt());
    }

    @Test
    void editDraftPassesFieldsThroughToService() throws Exception {
        when(reviewInboxService.editDraft(eq("user-1"), eq(5L), any(DraftEditRequestDTO.class)))
                .thenReturn(draftDto(5L));

        mockMvc.perform(put("/api/v1/life/finance/review-inbox/5")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 30.00, "merchant": "Corrected Merchant"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));

        verify(reviewInboxService).editDraft(eq("user-1"), eq(5L), any(DraftEditRequestDTO.class));
    }

    @Test
    void approveDraftReturnsCreatedExpense() throws Exception {
        ExpenseDTO expenseDto = new ExpenseDTO();
        expenseDto.setId(100L);
        when(reviewInboxService.approveDraft("user-1", 5L)).thenReturn(DraftApprovalResultDTO.created(expenseDto));

        mockMvc.perform(post("/api/v1/life/finance/review-inbox/5/approve")
                        .header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.expense.id").value(100));
    }

    @Test
    void approveDraftReturnsDuplicateFlagWhenAlreadyImported() throws Exception {
        ExpenseDTO expenseDto = new ExpenseDTO();
        expenseDto.setId(42L);
        when(reviewInboxService.approveDraft("user-1", 5L)).thenReturn(DraftApprovalResultDTO.duplicate(expenseDto));

        mockMvc.perform(post("/api/v1/life/finance/review-inbox/5/approve")
                        .header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.expense.id").value(42));
    }

    @Test
    void approveDraftPropagatesNotFoundFromService() throws Exception {
        when(reviewInboxService.approveDraft("user-1", 999L))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "draft_not_found"));

        mockMvc.perform(post("/api/v1/life/finance/review-inbox/999/approve")
                        .header("X-User-Id", "user-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectDraftReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/life/finance/review-inbox/5")
                        .header("X-User-Id", "user-1"))
                .andExpect(status().isNoContent());

        verify(reviewInboxService).rejectDraft("user-1", 5L);
    }
}
