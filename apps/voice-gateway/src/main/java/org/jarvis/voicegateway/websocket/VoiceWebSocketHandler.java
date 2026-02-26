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
        SessionContext ctx = new SessionContext(session, createRecognitionSession(defaultLanguage), defaultLanguage);
        sessions.put(session.getId(), ctx);

        sendJsonMessage(session, Map.of(
                "type", "STATE",
                "state", "CONNECTED",
                "language", ctx.language));
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
                    ctx.recognitionSession = createRecognitionSession(ctx.language);
                    chunkCounter.set(0);
                    log.info("🎤 Voice command started, correlationId={}, session={}, language={}",
                            correlationId, session.getId(), ctx.language);
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
        if (recognitionSession == null)
            return;

        if (recognitionSession.acceptWaveForm(data, data.length)) {
            String result = recognitionSession.getResult();
            log.info("🔇 Silence detected -> finalizing transcript, correlationId={}", ctx.correlationId);
            processRecognitionResult(ctx, result, true);
            ctx.recognitionSession = createRecognitionSession(ctx.language);
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
                        text); // Pass original text for LLM fallback
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
            ctx.recognitionSession = createRecognitionSession(normalized);
        } catch (RuntimeException e) {
            log.error("Failed to update language for session {}", session.getId(), e);
        }
    }

    private StreamingRecognitionSession createRecognitionSession(String language) {
        return sttService.createSession(language);
    }

    private void finalizeRecognition(WebSocketSession session) {
        SessionContext ctx = sessions.get(session.getId());
        if (ctx == null || ctx.recognitionSession == null) {
            log.warn("Finalize called but no session context/recognizer for {}", session.getId());
            return;
        }
        log.info("Finalizing recognition (END marker) for session {}", session.getId());
        String result = ctx.recognitionSession.getResult();
        processRecognitionResult(ctx, result, true);
        ctx.recognitionSession = createRecognitionSession(ctx.language);
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
                    correlationId);

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

    private static class SessionContext {
        final WebSocketSession session;
        StreamingRecognitionSession recognitionSession;
        String language;
        String correlationId;

        SessionContext(WebSocketSession session, StreamingRecognitionSession recognitionSession, String language) {
            this.session = session;
            this.recognitionSession = recognitionSession;
            this.language = language;
        }
    }
}
