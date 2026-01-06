package org.jarvis.orchestrator.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.service.OrchestratorService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/orchestrator")
@RequiredArgsConstructor
public class OrchestratorController {

    private final OrchestratorService orchestratorService;

    @PostMapping("/execute")
    public String execute(@RequestBody ExecuteRequest request) {
        String correlationId = request.correlationId() != null ? request.correlationId() : "N/A";
        String language = request.language() != null ? request.language() : "ru";

        if (request.intent() != null) {
            log.info("📥 Received intent execution: intent={}, params={}, lang={}, correlationId={}",
                    request.intent(), request.parameters(), language, correlationId);
            String result = orchestratorService.executeIntent(request.intent(), request.parameters(), language,
                    correlationId, request.text() != null ? request.text() : request.intent());
            log.info("📤 Intent execution result: '{}', correlationId={}", result, correlationId);
            return result;
        } else if (request.text() != null) {
            log.info("📥 Received text processing: text='{}', lang={}, correlationId={}",
                    request.text(), language, correlationId);
            String result = orchestratorService.processText(request.text(), language, correlationId);
            log.info("📤 Text processing result: '{}', correlationId={}", result, correlationId);
            return result;
        } else {
            log.warn("❌ Invalid request - no intent or text, correlationId={}", correlationId);
            throw new IllegalArgumentException("Either intent or text must be provided");
        }
    }

    public record ExecuteRequest(
            String intent,
            Map<String, String> parameters,
            String text,
            String language,
            String correlationId) {
    }
}
