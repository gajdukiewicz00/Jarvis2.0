package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.status.StatusReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Cross-subsystem status report. Backs the "Jarvis status report" scenario:
 * returns OK / DEGRADED / BROKEN for Voice, Vision, LLM, Memory, Desktop,
 * Commands and Infra plus an overall rollup.
 *
 * <p>Authenticated like the other gateway business endpoints (not in
 * {@code JwtAuthFilter.PUBLIC_PATHS}) so it never leaks topology to anonymous
 * callers.</p>
 */
@RestController
@RequestMapping("/api/v1/status")
@RequiredArgsConstructor
public class StatusReportController {

    private final StatusReportService statusReportService;

    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> report() {
        return ResponseEntity.ok(statusReportService.report());
    }
}
