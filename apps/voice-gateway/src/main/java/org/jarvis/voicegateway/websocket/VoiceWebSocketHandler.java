package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
import org.jarvis.voicegateway.service.intent.IntentRequest;
import org.jarvis.voicegateway.service.intent.IntentResult;
import org.jarvis.voicegateway.service.intent.IntentService;
import org.jarvis.voicegateway.util.LanguageDetector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final TtsService ttsService;
    private final SttService sttService;
    private final IntentService intentService;
    private final OrchestratorClient orchestratorClient;
    private final ObjectMapper objectMapper;

    // Store active sessions and their recognition sessions
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    @Value("${jarvis.vosk.default-language:ru-RU}")
    private String defaultLanguage;

    // Counter for audio chunk logging (to avoid spam)
    private final AtomicInteger chunkCounter = new AtomicInteger(0);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("New Voice WS connection: {}", session.getId());
        StreamingRecognitionSession recognitionSession = tryCreateRecognitionSession(defaultLanguage);
        SessionContext ctx = new SessionContext(
                session,
                recognitionSession,
                defaultLanguage,
                resolveUserId(session));
        sessions.put(session.getId(), ctx);

        sendJsonMessage(session, Map.of(
                "type", "STATE",
                "state", "CONNECTED",
                "language", ctx.language,
                "sttAvailable", recognitionSession != null));

        if (recognitionSession == null) {
            sendSttUnavailable(session, ctx.language, null);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Received text message: {}", payload);

        try {
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);
            String type = (String) msg.get("type");

            if ("START".equalsIgnoreCase(type)) {
                // New voice command session started
                String correlationId = (String) msg.get("correlationId");
                SessionContext ctx = sessions.get(session.getId());
                if (ctx != null) {
                    ctx.correlationId = correlationId;
                    // Reset recognition session for new command
                    if (ctx.recognitionSession != null) {
                        ctx.recognitionSession.close();
                    }
                    ctx.recognitionSession = tryCreateRecognitionSession(ctx.language);
                    chunkCounter.set(0);
                    log.info("🎤 Voice command started, correlationId={}, session={}, language={}",
                            correlationId, session.getId(), ctx.language);

                    if (ctx.recognitionSession == null) {
                        sendSttUnavailable(session, ctx.language, correlationId);
                    }
                }
            } else if ("CONFIG".equals(type)) {
                Map<String, Object> cfg = (Map<String, Object>) msg.get("config");
                if (cfg != null && cfg.containsKey("language")) {
                    String lang = String.valueOf(cfg.get("language"));
                    updateLanguage(session, lang);
                }
                log.info("Received config: {}", cfg);
            } else if ("END".equalsIgnoreCase(type)) {
                String correlationId = (String) msg.get("correlationId");
                SessionContext ctx = sessions.get(session.getId());
                if (ctx != null && correlationId != null) {
                    ctx.correlationId = correlationId; // Ensure it's set
                }
                log.info("⏹️ End-of-speech received, correlationId={}, session={}",
                        correlationId, session.getId());
                finalizeRecognition(session);
            } else if ("TIMEOUT".equalsIgnoreCase(type)) {
                // STT timeout - client didn't hear a final transcript
                String correlationId = (String) msg.get("correlationId");
                SessionContext ctx = sessions.get(session.getId());
                if (ctx != null) {
                    ctx.correlationId = correlationId;
                }
                log.info("⏰ STT timeout received, sending timeout phrase, correlationId={}", correlationId);
                handleSttTimeout(session, correlationId);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Invalid WS payload, sessionId={}", session.getId(), e);
            sendJsonMessage(session, Map.of("error", "invalid_payload"));
        } catch (IllegalArgumentException e) {
            log.warn("Bad WS input, sessionId={}", session.getId(), e);
            sendJsonMessage(session, Map.of("error", "bad_request"));
        } catch (RuntimeException e) {
            log.error("Error parsing text message, sessionId={}", session.getId(), e);
            sendJsonMessage(session, Map.of("error", "internal_error"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        SessionContext ctx = sessions.get(session.getId());
        if (ctx == null)
            return;

        ByteBuffer buffer = message.getPayload();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        // Log every 10th chunk to avoid spam
        int chunkNum = chunkCounter.incrementAndGet();
        if (chunkNum == 1 || chunkNum % 10 == 0) {
            log.debug("📦 Audio chunk #{}: {} bytes, correlationId={}, session={}",
                    chunkNum, data.length, ctx.correlationId, session.getId());
        }

        StreamingRecognitionSession recognitionSession = ctx.recognitionSession;
        if (recognitionSession == null) {
            sendSttUnavailable(session, ctx.language, ctx.correlationId);
            return;
        }

        if (recognitionSession.acceptWaveForm(data, data.length)) {
            String result = recognitionSession.getResult();
            log.info("🔇 Silence detected -> finalizing transcript, correlationId={}", ctx.correlationId);
            processRecognitionResult(ctx, result, true);
            ctx.recognitionSession = tryCreateRecognitionSession(ctx.language);
        } else {
            String partial = recognitionSession.getPartialResult();
            if (partial != null && !partial.isEmpty()) {
                log.debug("📝 Partial transcript: '{}', correlationId={}", partial, ctx.correlationId);
                processRecognitionResult(ctx, partial, false);
            }
        }
    }

    private void processRecognitionResult(SessionContext ctx, String json, boolean isFinal) {
        try {
            if (json == null || json.isEmpty())
                return;

            // JSON: {"text": "..."} or {"partial": "..."}
            Map<String, String> result = objectMapper.readValue(json, Map.class);
            String text = result.getOrDefault("text", result.get("partial"));

            if (text != null && !text.isEmpty()) {
                sendJsonMessage(ctx.session, Map.of(
                        "type", isFinal ? "TRANSCRIPT_FINAL" : "TRANSCRIPT_PARTIAL",
                        "text", text,
                        "correlationId", ctx.correlationId != null ? ctx.correlationId : ""));

                if (isFinal) {
                    log.info("📝 Final STT result: '{}', correlationId={}", text, ctx.correlationId);
                    handleCommand(ctx, text);
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Invalid recognition JSON, correlationId={}", ctx.correlationId, e);
        } catch (RuntimeException e) {
            log.error("Error processing recognition result, correlationId={}", ctx.correlationId, e);
        }
    }

    private void handleCommand(SessionContext ctx, String text) {
        // Auto-detect language from the transcript text (Cyrillic = Russian)
        String detectedLang = LanguageDetector.detect(text);
        // Use detected language, falling back to session language or default
        String lang = detectedLang != null ? detectedLang : (ctx.language != null ? ctx.language : defaultLanguage);
        String correlationId = ctx.correlationId != null ? ctx.correlationId : "no-correlation-id";
        String responseText = null;
        String action = "UNKNOWN";
        boolean handled = false;

        try {
            log.info("🎯 Processing command: '{}', correlationId={}, detectedLang={}", text, correlationId, lang);

            IntentResult intent = intentService.handle(IntentRequest.builder()
                    .text(text)
                    .language(lang)
                    .sessionId(ctx.session.getId())
                    .correlationId(correlationId)
                    .build());

            action = intent.getAction() != null ? intent.getAction() : "UNKNOWN";
            handled = intent.isHandled();
            responseText = intent.getResponse();

            log.info("🔍 Intent detected: action={}, handled={}, params={}, correlationId={}",
                    action, handled, intent.getParameters(), correlationId);

            // Always call orchestrator to get proper response (including for
            // UNKNOWN/fallback)
            // Orchestrator will handle: recognized intents → action + phrase, unknown →
            // fallback phrase
            String orchestratorAction = (handled && !"UNKNOWN".equals(action)) ? action : "fallback";
            log.info("📤 Sending intent to orchestrator: action={}, params={}, correlationId={}",
                    orchestratorAction, intent.getParameters(), correlationId);
            try {
                String orchestratorResponse = orchestratorClient.sendIntent(
                        orchestratorAction,
                        intent.getParameters(),
                        lang,
                        correlationId,
                        text,
                        ctx.userId); // Pass original text and user context for downstream routing
                log.info("📥 Orchestrator response: '{}', correlationId={}", orchestratorResponse, correlationId);

                // Use orchestrator response if available
                if (orchestratorResponse != null && !orchestratorResponse.isBlank()) {
                    responseText = orchestratorResponse;
                }
            } catch (RuntimeException e) {
                log.error("❌ Failed to call orchestrator, correlationId={}, error={}", correlationId, e.getMessage());
                // Don't override responseText - use intent's response as fallback
            }
        } catch (RuntimeException e) {
            log.error("❌ Error in handleCommand, correlationId={}", correlationId, e);
            responseText = lang.startsWith("ru")
                    ? "Произошла ошибка при обработке команды."
                    : "An error occurred while processing the command.";
        }

        if (responseText == null || responseText.isBlank()) {
            responseText = lang.startsWith("ru")
                    ? "Команда обработана."
                    : "Command processed.";
        }

        // Send response to client - wrapped in try-catch to prevent WS close
        try {
            sendJsonMessage(ctx.session, Map.of(
                    "type", "RESPONSE",
                    "action", action,
                    "handled", handled,
                    "text", responseText,
                    "correlationId", correlationId));
        } catch (RuntimeException e) {
            log.error("Failed to send RESPONSE message, correlationId={}", correlationId, e);
        }

        // Synthesize and send audio - wrapped in try-catch to prevent WS close
        try {
            log.debug("🔊 Synthesizing TTS: '{}', correlationId={}", responseText, correlationId);
            byte[] audio = ttsService.synthesize(responseText,
                    languageCode(lang),
                    voiceName(lang),
                    1.0,
                    0.0);
            if (audio == null || audio.length == 0) {
                log.warn("TTS returned no audio, correlationId={}", correlationId);
                return;
            }
            log.info("🔊 TTS synthesized {} bytes, correlationId={}", audio.length, correlationId);

            if (ctx.session.isOpen()) {
                ctx.session.sendMessage(new BinaryMessage(audio));
                log.debug("🔊 TTS audio sent, correlationId={}", correlationId);
            }
        } catch (IOException e) {
            log.error("Failed to send TTS audio over WS, correlationId={}", correlationId, e);
        } catch (RuntimeException e) {
            log.error("Failed to synthesize TTS audio, correlationId={}", correlationId, e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket connection closed: {}", session.getId());
        SessionContext ctx = sessions.remove(session.getId());
        if (ctx != null && ctx.recognitionSession != null) {
            ctx.recognitionSession.close();
        }
    }

    public int sendNotificationToUser(String userId, String message, String languageCode) {
        if (userId == null || userId.isBlank() || message == null || message.isBlank()) {
            return 0;
        }

        int delivered = (int) sessions.values().stream()
                .filter(ctx -> userId.equals(ctx.userId))
                .filter(ctx -> ctx.session.isOpen())
                .filter(ctx -> sendNotification(ctx, message, languageCode))
                .count();

        log.info("Sent voice notification to {} active sessions for user {}", delivered, userId);
        return delivered;
    }

    private void sendJsonMessage(WebSocketSession session, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.error("Failed to send JSON message", e);
        }
    }

    private boolean sendNotification(SessionContext ctx, String message, String languageCode) {
        String lang = languageCode != null && !languageCode.isBlank()
                ? languageCode
                : (ctx.language != null ? ctx.language : defaultLanguage);
        String correlationId = "notification-" + UUID.randomUUID();

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "RESPONSE",
                    "action", "NOTIFY",
                    "handled", true,
                    "text", message,
                    "correlationId", correlationId));
            if (ctx.session.isOpen()) {
                ctx.session.sendMessage(new TextMessage(json));
            }

            byte[] audio = ttsService.synthesize(
                    message,
                    languageCode(lang),
                    voiceName(lang),
                    1.0,
                    0.0);
            if (audio == null || audio.length == 0) {
                log.warn("Voice notification TTS returned no audio, userId={}, correlationId={}",
                        ctx.userId, correlationId);
                return true;
            }
            if (ctx.session.isOpen()) {
                ctx.session.sendMessage(new BinaryMessage(audio));
            }
            return true;
        } catch (IOException e) {
            log.error("Failed to send voice notification to session {}", ctx.session.getId(), e);
            return false;
        } catch (RuntimeException e) {
            log.error("Failed to synthesize voice notification for session {}", ctx.session.getId(), e);
            return false;
        }
    }

    private void updateLanguage(WebSocketSession session, String language) {
        SessionContext ctx = sessions.get(session.getId());
        if (ctx == null)
            return;
        if (language == null || language.isBlank())
            return;

        String normalized = language.toLowerCase();
        log.info("Updating session {} language to {}", session.getId(), normalized);
        try {
            if (ctx.recognitionSession != null) {
                ctx.recognitionSession.close();
            }
            ctx.language = normalized;
            ctx.recognitionSession = tryCreateRecognitionSession(normalized);
            if (ctx.recognitionSession == null) {
                sendSttUnavailable(session, normalized, ctx.correlationId);
            }
        } catch (RuntimeException e) {
            log.error("Failed to update language for session {}", session.getId(), e);
        }
    }

    private StreamingRecognitionSession createRecognitionSession(String language) {
        return sttService.createSession(language);
    }

    private StreamingRecognitionSession tryCreateRecognitionSession(String language) {
        try {
            return createRecognitionSession(language);
        } catch (RuntimeException e) {
            log.warn("STT is unavailable for language {}: {}", language, e.getMessage());
            return null;
        }
    }

    private void finalizeRecognition(WebSocketSession session) {
        SessionContext ctx = sessions.get(session.getId());
        if (ctx == null || ctx.recognitionSession == null) {
            log.warn("Finalize called but no session context/recognizer for {}", session.getId());
            sendSttUnavailable(session, ctx != null ? ctx.language : defaultLanguage, ctx != null ? ctx.correlationId : null);
            return;
        }
        log.info("Finalizing recognition (END marker) for session {}", session.getId());
        String result = ctx.recognitionSession.getResult();
        processRecognitionResult(ctx, result, true);
        ctx.recognitionSession = tryCreateRecognitionSession(ctx.language);
    }

    private void sendSttUnavailable(WebSocketSession session, String language, String correlationId) {
        String lang = language != null ? language : defaultLanguage;
        sendJsonMessage(session, Map.of(
                "type", "RESPONSE",
                "action", "STT_UNAVAILABLE",
                "handled", false,
                "text", sttUnavailableMessage(lang),
                "correlationId", correlationId != null ? correlationId : ""));
    }

    private String sttUnavailableMessage(String language) {
        String normalized = language != null ? language.toLowerCase() : "";
        if (normalized.startsWith("en")) {
            return "Speech recognition is unavailable. Install a local STT model to use voice commands.";
        }
        return "Распознавание речи недоступно. Установите локальную STT-модель для голосовых команд.";
    }

    /**
     * Handle STT timeout - speak "Sir, I couldn't hear you" phrase.
     */
    private void handleSttTimeout(WebSocketSession session, String correlationId) {
        SessionContext ctx = sessions.get(session.getId());
        String lang = ctx != null && ctx.language != null ? ctx.language : defaultLanguage;

        log.info("⏰ Generating STT timeout response, lang={}, correlationId={}", lang, correlationId);

        // Get timeout phrase from orchestrator
        try {
            String timeoutResponse = orchestratorClient.sendIntent(
                    "STT_TIMEOUT",
                    java.util.Map.of(),
                    lang,
                    correlationId,
                    null,
                    ctx != null ? ctx.userId : null);

            if (timeoutResponse == null || timeoutResponse.isBlank()) {
                timeoutResponse = lang.startsWith("en")
                        ? "Sir, I couldn't hear you well."
                        : "Сэр, я вас плохо расслышал.";
            }

            log.info("⏰ Timeout response: '{}', correlationId={}", timeoutResponse, correlationId);

            // Send response to client
            sendJsonMessage(session, java.util.Map.of(
                    "type", "RESPONSE",
                    "action", "STT_TIMEOUT",
                    "handled", true,
                    "text", timeoutResponse,
                    "correlationId", correlationId != null ? correlationId : ""));

            // Synthesize and send TTS audio
            byte[] audio = ttsService.synthesize(timeoutResponse,
                    languageCode(lang),
                    voiceName(lang),
                    1.0,
                    0.0);
            if (audio == null || audio.length == 0) {
                log.warn("STT timeout TTS returned no audio, correlationId={}", correlationId);
                return;
            }

            if (session.isOpen()) {
                session.sendMessage(new BinaryMessage(audio));
            }
        } catch (IOException e) {
            log.error("Failed to send STT timeout audio over WS, correlationId={}", correlationId, e);
        } catch (RuntimeException e) {
            log.error("Failed to handle STT timeout, correlationId={}", correlationId, e);
        }
    }

    private String languageCode(String lang) {
        if (lang == null)
            return "ru-RU";
        return lang.startsWith("en") ? "en-US" : "ru-RU";
    }

    private String voiceName(String lang) {
        return lang != null && lang.startsWith("en")
                ? "en-US-Wavenet-D"
                : "ru-RU-Wavenet-A";
    }

    private String firstHeader(WebSocketSession session, String headerName) {
        if (session.getHandshakeHeaders() == null) {
            return null;
        }
        String value = session.getHandshakeHeaders().getFirst(headerName);
        return value != null && !value.isBlank() ? value : null;
    }

    private String resolveUserId(WebSocketSession session) {
        String headerUserId = firstHeader(session, "X-User-Id");
        if (headerUserId != null) {
            return headerUserId;
        }
        if (session.getPrincipal() != null && session.getPrincipal().getName() != null
                && !session.getPrincipal().getName().isBlank()) {
            return session.getPrincipal().getName();
        }
        return null;
    }

    private static class SessionContext {
        final WebSocketSession session;
        StreamingRecognitionSession recognitionSession;
        String language;
        String correlationId;
        String userId;

        SessionContext(WebSocketSession session, StreamingRecognitionSession recognitionSession, String language,
                String userId) {
            this.session = session;
            this.recognitionSession = recognitionSession;
            this.language = language;
            this.userId = userId;
        }
    }
}
