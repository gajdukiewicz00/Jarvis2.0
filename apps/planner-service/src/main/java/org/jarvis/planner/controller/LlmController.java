package org.jarvis.planner.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.planner.service.LlmEnhancementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LLM integration endpoints
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/planner/llm")
@RequiredArgsConstructor
public class LlmController {
    
    private final LlmEnhancementService llmService;
    
    /**
     * Generate document via LLM
     */
    @PostMapping("/generate-document")
    public ResponseEntity<Map<String, String>> generateDocument(
            @RequestParam String userId,
            @RequestParam String documentType,
            @RequestBody String context
    ) {
        log.info("Generating {} for user: {}", documentType, userId);
        
        String result = llmService.generateDocument(userId, documentType, context);
        
        return ResponseEntity.ok(Map.of(
            "documentType", documentType,
            "content", result
        ));
    }
    
    /**
     * Parse natural language task
     */
    @PostMapping("/parse-task")
    public ResponseEntity<Map<String, String>> parseTask(
            @RequestParam String userId,
            @RequestBody String naturalLanguage
    ) {
        log.info("Parsing NL task for user: {}: {}", userId, naturalLanguage);
        
        String result = llmService.parseNaturalLanguageTask(userId, naturalLanguage);
        
        return ResponseEntity.ok(Map.of(
            "input", naturalLanguage,
            "parsed", result
        ));
    }
}
