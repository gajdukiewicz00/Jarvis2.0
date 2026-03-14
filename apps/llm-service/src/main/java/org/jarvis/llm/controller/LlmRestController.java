package org.jarvis.llm.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.logging.LogSanitizer;
import org.jarvis.llm.dto.ChatRequestDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.dto.DialogRequest;
import org.jarvis.llm.dto.DialogResponse;
import org.jarvis.llm.service.LlmService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for LLM service.
 * 
 * <h2>Manual Testing (curl)</h2>
 * <pre>
 * # 1. Health check (should return "healthy" when llm-server is up)
 * curl -s http://localhost:8091/api/v1/llm/health | jq
 * 
 * # 2. Dialog request (user-profile optional - works even if unavailable)
 * curl -s -X POST http://localhost:8091/api/v1/llm/dialog \
 *   -H "Content-Type: application/json" \
 *   -H "X-Correlation-ID: test-$(date +%s)" \
 *   -d '{"sessionId":"test-session","userId":"user1","input":"Привет, как дела?","mode":"dialog"}' | jq
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class LlmRestController {

    private final LlmService llmService;

    @Value("${logging.pii.enabled:true}")
    private boolean piiLoggingEnabled = true;

    @Value("${logging.pii.allowQuerySnippet:false}")
    private boolean piiAllowQuerySnippet = false;

    @Value("${logging.pii.querySnippetMaxLength:32}")
    private int piiQuerySnippetMaxLength = 32;

    /**
     * Process chat request
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(
            @Valid @RequestBody ChatRequestDto request,
            Authentication authentication,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {

        if (correlationId == null) {
            correlationId = java.util.UUID.randomUUID().toString();
        }

        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            log.warn("Invalid chat request: missing messages, correlationId={}", correlationId);
            return ResponseEntity.badRequest().build();
        }

        log.info("Received REST chat request for session={}, correlationId={}",
                logSanitizer().sanitizeId(request.getSessionId()),
                correlationId);

        try {
            // Get last user message from request
            String userMessage = request.getMessages().get(request.getMessages().size() - 1).getContent();
            if (userMessage == null || userMessage.isBlank()) {
                log.warn("Invalid chat request: last user message is empty, correlationId={}", correlationId);
                return ResponseEntity.badRequest().build();
            }

            ChatResponseDto response = llmService.processMessage(
                    request.getSessionId(),
                    resolveEffectiveUserId(authentication),
                    userMessage,
                    correlationId);

            return ResponseEntity.ok(response);

        } catch (org.jarvis.llm.client.LlmClient.LlmClientException e) {
            log.error("LLM Client error: {}", e.getMessage());
            Throwable cause = e.getCause();
            if (cause instanceof org.springframework.web.client.ResourceAccessException) {
                return ResponseEntity.status(504).build(); // Gateway Timeout
            } else if (cause instanceof org.springframework.web.client.HttpServerErrorException) {
                return ResponseEntity.status(503).build(); // Service Unavailable
            } else if (cause instanceof org.springframework.web.client.HttpClientErrorException) {
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.status(503).build();
        } catch (RuntimeException e) {
            log.error("Error processing chat request: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Process dialog mode interaction.
     * 
     * Unlike /chat, this endpoint:
     * - Maintains conversation context across requests
     * - Supports dialog/command/scenario modes
     * - Detects exit phrases to end dialog mode
     * - Returns structured response with shouldContinue flag
     * 
     * @param request Dialog request with sessionId, userId, input, mode
     * @param correlationId Optional correlation ID for tracing
     * @return DialogResponse with reply and metadata
     */
    @PostMapping("/dialog")
    public ResponseEntity<DialogResponse> dialog(
            @RequestBody DialogRequest request,
            Authentication authentication,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        
        if (correlationId == null) {
            correlationId = java.util.UUID.randomUUID().toString();
        }
        LogSanitizer sanitizer = logSanitizer();
        if (request.getUserId() == null || request.getUserId().isBlank()) {
            request.setUserId(resolveEffectiveUserId(authentication));
        }

        log.info("📝 Dialog request: sessionId={}, mode={}, correlationId={}", 
                sanitizer.sanitizeId(request.getSessionId()), request.getMode(), correlationId);
        
        try {
            DialogResponse response = llmService.processDialog(request, correlationId);
            return ResponseEntity.ok(response);
            
        } catch (org.jarvis.llm.client.LlmClient.LlmClientException e) {
            log.error("LLM Client error in dialog: {}", e.getMessage());
            
            // Return fallback response instead of error
            return ResponseEntity.ok(DialogResponse.builder()
                    .sessionId(request.getSessionId())
                    .reply("Извини, сейчас не могу ответить. Попробуй позже.")
                    .shouldContinue(true)
                    .mode("dialog")
                    .build());
                    
        } catch (RuntimeException e) {
            log.error("Error processing dialog request: {}", e.getMessage(), e);
            
            return ResponseEntity.ok(DialogResponse.builder()
                    .sessionId(request.getSessionId())
                    .reply("Произошла ошибка. Попробуй ещё раз.")
                    .shouldContinue(true)
                    .mode("dialog")
                    .build());
        }
    }

    /**
     * Clear session history
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        log.info("Clearing session={}", logSanitizer().sanitizeId(sessionId));
        llmService.clearSession(sessionId);
        return ResponseEntity.ok().build();
    }

    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean available = llmService.isAvailable();

        return ResponseEntity.ok(Map.of(
                "status", available ? "healthy" : "degraded",
                "llm_server_available", available));
    }

    private LogSanitizer logSanitizer() {
        return new LogSanitizer(piiLoggingEnabled, piiAllowQuerySnippet, piiQuerySnippetMaxLength);
    }

    private String resolveEffectiveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object details = authentication.getDetails();
        if (details instanceof String detailsString && detailsString.startsWith("delegated-by:")) {
            return authentication.getName();
        }

        boolean internalOnly = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("SVC_INTERNAL"::equals);
        if (internalOnly) {
            return null;
        }

        return authentication.getName();
    }
}
