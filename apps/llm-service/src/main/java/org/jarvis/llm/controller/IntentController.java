package org.jarvis.llm.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.dto.IntentRequest;
import org.jarvis.llm.dto.IntentResponse;
import org.jarvis.llm.service.IntentClassifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 3 — exposes the host router model as a fast-intent classification
 * endpoint. Called by nlp-service. No other service is allowed to bypass
 * llm-service to reach the router (SPEC-1).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class IntentController {

    private final IntentClassifier classifier;

    @PostMapping("/intent")
    public ResponseEntity<IntentResponse> classify(@Valid @RequestBody IntentRequest request) {
        IntentResponse response = classifier.classify(request);
        return ResponseEntity.ok(response);
    }
}
