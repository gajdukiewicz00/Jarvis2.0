package org.jarvis.llm.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.orchestrator.LlmOrchestratorService;
import org.jarvis.llm.orchestrator.dto.OrchestrationRequest;
import org.jarvis.llm.orchestrator.dto.OrchestrationResponse;
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

    @PostMapping("/orchestrate")
    public ResponseEntity<OrchestrationResponse> orchestrate(
            @Valid @RequestBody OrchestrationRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {

        if (correlationId == null) {
            correlationId = java.util.UUID.randomUUID().toString();
        }

        log.info("🧠 Orchestration request: sessionId={}, userId={}, correlationId={}",
                request.getSessionId(), request.getUserId(), correlationId);

        OrchestrationResponse response = orchestratorService.orchestrate(request, correlationId);
        return ResponseEntity.ok(response);
    }
}
