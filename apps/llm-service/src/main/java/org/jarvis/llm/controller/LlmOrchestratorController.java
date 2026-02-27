package org.jarvis.llm.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.logging.LogSanitizer;
import org.jarvis.llm.orchestrator.LlmOrchestratorService;
import org.jarvis.llm.orchestrator.dto.OrchestrationRequest;
import org.jarvis.llm.orchestrator.dto.OrchestrationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
@Validated
public class LlmOrchestratorController {

    private final LlmOrchestratorService orchestratorService;

    @Value("${logging.pii.enabled:true}")
    private boolean piiLoggingEnabled = true;

    @Value("${logging.pii.allowQuerySnippet:false}")
    private boolean piiAllowQuerySnippet = false;

    @Value("${logging.pii.querySnippetMaxLength:32}")
    private int piiQuerySnippetMaxLength = 32;

    @PostMapping("/orchestrate")
    public ResponseEntity<OrchestrationResponse> orchestrate(
            @Valid @RequestBody OrchestrationRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {

        if (correlationId == null) {
            correlationId = java.util.UUID.randomUUID().toString();
        }

        LogSanitizer sanitizer = logSanitizer();
        log.info("🧠 Orchestration request: sessionId={}, userId={}, correlationId={}",
                sanitizer.sanitizeId(request.getSessionId()),
                sanitizer.sanitizeId(request.getUserId()),
                correlationId);

        OrchestrationResponse response = orchestratorService.orchestrate(request, correlationId);
        return ResponseEntity.ok(response);
    }

    private LogSanitizer logSanitizer() {
        return new LogSanitizer(piiLoggingEnabled, piiAllowQuerySnippet, piiQuerySnippetMaxLength);
    }
}
