package org.jarvis.llm.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.logging.LogSanitizer;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.dto.WebSocketChatMessage;
import org.jarvis.llm.dto.WebSocketChatResponse;
import org.jarvis.llm.service.LlmService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;

import java.time.Instant;

/**
 * WebSocket controller for LLM chat
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class LlmWebSocketController {

    private final LlmService llmService;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${logging.pii.enabled:true}")
    private boolean piiLoggingEnabled = true;

    @Value("${logging.pii.allowQuerySnippet:false}")
    private boolean piiAllowQuerySnippet = false;

    @Value("${logging.pii.querySnippetMaxLength:32}")
    private int piiQuerySnippetMaxLength = 32;

    /**
     * Handle incoming chat messages from WebSocket
     * 
     * Client sends to: /app/llm-chat
     * Client subscribes to: /topic/llm-response/{sessionId}
     */
    @MessageMapping("/llm-chat")
    public void handleChatMessage(WebSocketChatMessage message) {
        String sessionId = message.getSessionId();
        String userMessage = message.getMessage();
        LogSanitizer sanitizer = logSanitizer();

        log.info("Received WebSocket message from session={}", sanitizer.sanitizeId(sessionId));

        try {
            // Process message with LLM
            String correlationId = java.util.UUID.randomUUID().toString();
            ChatResponseDto llmResponse = llmService.processMessage(sessionId, userMessage, correlationId);

            // Build WebSocket response
            WebSocketChatResponse response = new WebSocketChatResponse(
                    sessionId,
                    llmResponse.getReply(),
                    Instant.now(),
                    llmResponse.getProcessingTimeMs());

            // Send response to specific session topic
            messagingTemplate.convertAndSend("/topic/llm-response/" + sessionId, response);

            log.info("Sent response to session={} (reply chars={}, {}ms)",
                    sanitizer.sanitizeId(sessionId),
                    response.getReply().length(),
                    response.getProcessingTimeMs());

        } catch (RuntimeException e) {
            log.error("Error processing WebSocket message for session={}: {}",
                    sanitizer.sanitizeId(sessionId), e.getMessage(), e);

            // Send error response
            WebSocketChatResponse errorResponse = new WebSocketChatResponse(
                    sessionId,
                    "Извините, произошла ошибка при обработке вашего сообщения.",
                    Instant.now(),
                    0);
            messagingTemplate.convertAndSend("/topic/llm-response/" + sessionId, errorResponse);
        }
    }

    private LogSanitizer logSanitizer() {
        return new LogSanitizer(piiLoggingEnabled, piiAllowQuerySnippet, piiQuerySnippetMaxLength);
    }
}
