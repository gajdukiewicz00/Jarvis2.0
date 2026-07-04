package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.service.LlmEnhancementService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LLM integration endpoints. Delegates generation to llm-service (host GPU Qwen3-14B).
 * Only active when planner.llm.enabled=true.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/planner/llm")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "planner.llm.enabled", havingValue = "true")
public class LlmController {

    private final LlmEnhancementService enhancementService;

    /** Generate a document via the LLM. */
    @PostMapping("/generate-document")
    public ResponseEntity<Map<String, String>> generateDocument(
            Authentication authentication,
            @RequestParam String documentType,
            @RequestBody String context) {
        String userId = authentication.getName();
        log.info("Generating {} for user: {}", documentType, userId);
        try {
            String result = enhancementService.generateDocument(userId, documentType, context);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "documentType", documentType,
                    "content", result));
        } catch (RuntimeException e) {
            log.error("Document generation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "LLM_UNAVAILABLE",
                    "message", "llm-service is not reachable"));
        }
    }

    /** Parse a natural-language task into structured JSON via the LLM. */
    @PostMapping("/parse-task")
    public ResponseEntity<Map<String, String>> parseTask(
            Authentication authentication,
            @RequestBody String naturalLanguage) {
        String userId = authentication.getName();
        log.info("Parsing NL task for user: {}", userId);
        try {
            String result = enhancementService.parseNaturalLanguageTask(userId, naturalLanguage);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "input", naturalLanguage,
                    "parsed", result));
        } catch (RuntimeException e) {
            log.error("Task parsing failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "LLM_UNAVAILABLE",
                    "message", "llm-service is not reachable"));
        }
    }

    /** Generate a smart planning recommendation via the LLM. */
    @PostMapping("/recommend")
    public ResponseEntity<Map<String, String>> recommend(
            Authentication authentication,
            @RequestBody String context) {
        String userId = authentication.getName();
        log.info("Generating recommendation for user: {}", userId);
        try {
            String result = enhancementService.generateSmartRecommendation(userId, context);
            return ResponseEntity.ok(Map.of("status", "OK", "recommendation", result));
        } catch (RuntimeException e) {
            log.error("Recommendation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "LLM_UNAVAILABLE",
                    "message", "llm-service is not reachable"));
        }
    }
}
