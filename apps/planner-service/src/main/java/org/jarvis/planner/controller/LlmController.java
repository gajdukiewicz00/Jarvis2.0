package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LLM integration endpoints
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/planner/llm")
@RequiredArgsConstructor
@ConditionalOnProperty(value = "planner.llm.enabled", havingValue = "true")
public class LlmController {

    /**
     * Generate document via LLM
     */
    @PostMapping("/generate-document")
    public ResponseEntity<Map<String, String>> generateDocument(
            Authentication authentication,
            @RequestParam String documentType,
            @RequestBody String context
    ) {
        String userId = authentication.getName();
        log.info("Generating {} for user: {}", documentType, userId);

        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
            "status", "NOT_IMPLEMENTED",
            "documentType", documentType,
            "message", "planner-service does not expose placeholder LLM document generation anymore; use llm-service directly"
        ));
    }
    
    /**
     * Parse natural language task
     */
    @PostMapping("/parse-task")
    public ResponseEntity<Map<String, String>> parseTask(
            Authentication authentication,
            @RequestBody String naturalLanguage
    ) {
        String userId = authentication.getName();
        log.info("Parsing NL task for user: {}: {}", userId, naturalLanguage);

        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
            "status", "NOT_IMPLEMENTED",
            "input", naturalLanguage,
            "message", "planner-service does not expose placeholder LLM task parsing; use llm-service directly"
        ));
    }
}
