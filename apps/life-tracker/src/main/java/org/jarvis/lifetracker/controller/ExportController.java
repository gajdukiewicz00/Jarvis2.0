package org.jarvis.lifetracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.lifetracker.repository.WellnessLogRepository;
import org.jarvis.lifetracker.service.FinanceService;
import org.jarvis.lifetracker.util.UserContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exports the user's own life-tracker data (data ownership / GDPR-style takeout). */
@RestController
@RequestMapping("/api/v1/life")
@RequiredArgsConstructor
public class ExportController {

    private final WellnessLogRepository wellnessRepository;
    private final FinanceService financeService;

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(HttpServletRequest http) {
        String userId = requireUserId(http);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", userId);
        out.put("exportedAt", Instant.now().toString());
        out.put("finance", financeService.listTransactions(userId, null, null, null, null));
        out.put("wellness", wellnessRepository.findTop200ByUserIdOrderByLoggedAtDesc(userId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jarvis-life-export.json\"")
                .body(out);
    }

    private String requireUserId(HttpServletRequest request) {
        String userId = UserContext.getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing_user_id");
        }
        return userId;
    }
}
