package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.lifetracker.dto.DraftApprovalResultDTO;
import org.jarvis.lifetracker.dto.DraftEditRequestDTO;
import org.jarvis.lifetracker.dto.ExpenseDraftDTO;
import org.jarvis.lifetracker.dto.ReviewInboxPageDTO;
import org.jarvis.lifetracker.service.ReviewInboxService;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * FINANCE-REVIEW: manual review inbox for MEDIUM/LOW-confidence bank-notification drafts
 * (see {@link BankNotificationController} for where drafts are created).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/life/finance/review-inbox")
@RequiredArgsConstructor
public class ReviewInboxController {

    private final ReviewInboxService reviewInboxService;

    @GetMapping
    public ReviewInboxPageDTO listInbox(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {
        return reviewInboxService.listInbox(requireUserId(httpRequest), page, size);
    }

    @PutMapping("/{id}")
    public ExpenseDraftDTO editDraft(
            @PathVariable Long id,
            @RequestBody DraftEditRequestDTO request,
            HttpServletRequest httpRequest) {
        return reviewInboxService.editDraft(requireUserId(httpRequest), id, request);
    }

    @PostMapping("/{id}/approve")
    public DraftApprovalResultDTO approveDraft(@PathVariable Long id, HttpServletRequest httpRequest) {
        return reviewInboxService.approveDraft(requireUserId(httpRequest), id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> rejectDraft(@PathVariable Long id, HttpServletRequest httpRequest) {
        reviewInboxService.rejectDraft(requireUserId(httpRequest), id);
        return ResponseEntity.noContent().build();
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
