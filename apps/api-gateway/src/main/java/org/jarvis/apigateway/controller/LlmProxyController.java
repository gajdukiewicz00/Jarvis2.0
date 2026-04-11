package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.LlmServiceClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class LlmProxyController {

    private final LlmServiceClient llmServiceClient;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return llmServiceClient.health();
    }

    @GetMapping("/runtime")
    public ResponseEntity<String> runtime() {
        log.debug("Proxying GET /api/v1/llm/runtime to llm-service");
        return llmServiceClient.runtime();
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chat(@RequestBody String body) {
        log.info("Proxying POST /api/v1/llm/chat to llm-service");
        return llmServiceClient.chat(body);
    }

    @PostMapping(value = "/dialog", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> dialog(@RequestBody String body) {
        log.info("Proxying POST /api/v1/llm/dialog to llm-service");
        return llmServiceClient.dialog(body);
    }

    @PostMapping(value = "/orchestrate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> orchestrate(@RequestBody String body) {
        log.info("Proxying POST /api/v1/llm/orchestrate to llm-service");
        return llmServiceClient.orchestrate(body);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        log.info("Proxying DELETE /api/v1/llm/session/{} to llm-service", sessionId);
        return llmServiceClient.clearSession(sessionId);
    }
}
