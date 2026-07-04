package org.jarvis.llm.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.logging.LogSanitizer;
import org.jarvis.llm.dto.ChatRequestDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.dto.DialogRequest;
import org.jarvis.llm.dto.DialogResponse;
import org.jarvis.llm.service.AiRuntimeStatusService;
import org.jarvis.llm.service.LlmService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.LinkedHashMap;

/**
 * REST controller for LLM service.
 * 
 * <h2>Manual Testing (curl)</h2>
 * <pre>
 * # 1. Health check (should return "healthy" when host-model-daemon is reachable)
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
    private final AiRuntimeStatusService aiRuntimeStatusService;
    private final org.jarvis.llm.service.LlmLifecycleManager lifecycleManager;
    private final org.jarvis.llm.service.LlmAdmissionController admissionController;

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
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-Model-Profile", required = false) String modelProfile) {

        if (correlationId == null) {
            correlationId = java.util.UUID.randomUUID().toString();
        }

        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            log.warn("Invalid chat request: missing messages, correlationId={}", correlationId);
            return ResponseEntity.badRequest().build();
        }

        log.info("Received REST chat request for session={}, profile={}, correlationId={}",
                logSanitizer().sanitizeId(request.getSessionId()),
                modelProfile != null ? modelProfile : "default",
                correlationId);

        try {
            String userMessage = request.getMessages().get(request.getMessages().size() - 1).getContent();
            if (userMessage == null || userMessage.isBlank()) {
                log.warn("Invalid chat request: last user message is empty, correlationId={}", correlationId);
                return ResponseEntity.badRequest().build();
            }

            ChatResponseDto response = llmService.processMessage(
                    request.getSessionId(),
                    resolveEffectiveUserId(authentication),
                    userMessage,
                    correlationId,
                    !isInternalRequest(authentication),
                    modelProfile);

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
        } catch (org.jarvis.llm.client.MemoryClient.MemoryClientException e) {
            log.error("Memory Client error: {}", e.getMessage());
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
            Throwable cause = e.getCause();
            if (cause instanceof org.springframework.web.client.ResourceAccessException) {
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (org.jarvis.llm.client.MemoryClient.MemoryClientException e) {
            log.error("Memory Client error in dialog: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (RuntimeException e) {
            log.error("Error processing dialog request: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
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
        lifecycleManager.refreshState();

        Map<String, Object> runtime = aiRuntimeStatusService.describe();
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) runtime.get("llm");
        @SuppressWarnings("unchecked")
        Map<String, Object> memory = (Map<String, Object>) runtime.get("memory");
        @SuppressWarnings("unchecked")
        Map<String, Object> routing = (Map<String, Object>) runtime.get("routing");

        boolean llmAvailable = Boolean.TRUE.equals(llm.get("available"));
        boolean memoryEnabled = Boolean.TRUE.equals(memory.get("enabled")) && Boolean.TRUE.equals(memory.get("serviceEnabled"));
        boolean memoryAvailable = Boolean.TRUE.equals(memory.get("available"));

        org.jarvis.llm.service.LlmLifecycleManager.State state = lifecycleManager.getState();
        boolean healthy = state == org.jarvis.llm.service.LlmLifecycleManager.State.READY;
        boolean usable = lifecycleManager.isUsable();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", healthy ? "healthy" : (usable ? "degraded" : state.name().toLowerCase()));
        response.put("lifecycle_state", state.name());
        response.put("lifecycle_reason", lifecycleManager.getStateReason());
        response.put("warmup_complete", lifecycleManager.isWarmupComplete());
        response.put("host_daemon_available", llmAvailable);
        response.put("host_daemon_base_url", llm.get("baseUrl"));
        response.put("host_daemon_health_url", routing.get("hostDaemonHealthUrl"));
        response.put("llama_cpp_chat_completions_url", routing.get("llamaCppChatCompletionsUrl"));
        response.put("memory_available", memoryAvailable);
        response.put("memory_enabled", memoryEnabled);
        response.put("active_inferences", admissionController.getActiveInferences());
        response.put("queue_depth", admissionController.getQueueDepth());
        response.put("full_local_ai_readiness", runtime.get("fullLocalAiReadiness"));
        response.put("configured_provider", llm.get("configuredProvider"));
        response.put("effective_provider", llm.get("effectiveProvider"));
        response.put("configured_model", llm.get("configuredModel"));
        response.put("effective_model", llm.get("effectiveModel"));
        response.put("local_model_profile", runtime.get("localModelProfile"));

        HttpStatus httpStatus = healthy ? HttpStatus.OK
                : usable ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(response);
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

        if (isInternalRequest(authentication)) {
            return null;
        }

        return authentication.getName();
    }

    private boolean isInternalRequest(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("SVC_INTERNAL"::equals);
    }
}
