package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.rules.RuleBasedVoiceCommandService;
import org.jarvis.voicegateway.service.StreamingRecognitionSession;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
import org.jarvis.voicegateway.rules.VoiceCommandActionDispatcher;
import org.jarvis.voicegateway.rules.VoiceCommandCatalog;
import org.jarvis.voicegateway.service.intent.IntentRequest;
import org.jarvis.voicegateway.service.intent.IntentResult;
import org.jarvis.voicegateway.service.intent.IntentService;
import org.jarvis.voicegateway.util.LanguageDetector;
import org.jarvis.voicegateway.voice.VoiceOutputService;
import org.jarvis.voicegateway.voice.WavResponseRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private final VoiceOutputService voiceOutputService;
    private final SttService sttService;
    private final TtsService ttsService;
    private final RuleBasedVoiceCommandService ruleBasedVoiceCommandService;
    private final VoiceCommandActionDispatcher voiceCommandActionDispatcher;
    private final WavResponseRegistry wavResponseRegistry;
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
        String userId = resolveUserId(session);
        String effectiveLanguage = canonicalRecognitionLanguage(defaultLanguage);
        StreamingRecognitionSession recognitionSession = tryCreateRecognitionSession(effectiveLanguage);
        SessionContext ctx = new SessionContext(
                session,
                recognitionSession,
                effectiveLanguage,
                userId);
        sessions.put(session.getId(), ctx);

        Map<String, Object> ttsRuntime = ttsService.describeRuntime();
        boolean ttsAvailable = Boolean.TRUE.equals(ttsRuntime.get("available"));

        log.info("🎙️ Voice WS open: session={}, userId={}, username={}, requestedDefaultLanguage={}, effectiveRecognitionLanguage={}, sttAvailable={}, ttsAvailable={}",
                session.getId(),
                userId,
                firstHeader(session, "X-Username"),
                defaultLanguage,
                ctx.language,
                recognitionSession != null,
                ttsAvailable);

        Map<String, Object> statePayload = new LinkedHashMap<>();
        statePayload.put("type", "STATE");
        statePayload.put("state", "CONNECTED");
        statePayload.put("language", ctx.language);
        statePayload.put("sttAvailable", recognitionSession != null);
        statePayload.put("ttsAvailable", ttsAvailable);
        statePayload.put("ttsStatus", String.valueOf(ttsRuntime.getOrDefault("status", "unknown")));
        Object ttsReason = ttsRuntime.get("reason");
        if (ttsReason != null) {
            statePayload.put("ttsReason", String.valueOf(ttsReason));
        }
        sendJsonMessage(session, statePayload);

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
                Object rawLanguage = msg.get("language");
                String requestedLanguage = rawLanguage != null ? String.valueOf(rawLanguage) : null;
                SessionContext ctx = sessions.get(session.getId());
                if (ctx != null) {
                    ctx.correlationId = correlationId;
                    ctx.language = canonicalRecognitionLanguage(requestedLanguage != null ? requestedLanguage : ctx.language);
                    // Reset recognition session for new command
                    if (ctx.recognitionSession != null) {
                        ctx.recognitionSession.close();
                    }
                    ctx.recognitionSession = tryCreateRecognitionSession(ctx.language);
                    chunkCounter.set(0);
                    log.info("🎤 Voice command started, correlationId={}, session={}, requestedLanguage={}, effectiveRecognitionLanguage={}",
                            correlationId, session.getId(), requestedLanguage, ctx.language);

                    if (ctx.recognitionSession == null) {
                        sendSttUnavailable(session, ctx.language, correlationId);
                    }
                }
            } else if ("CONFIG".equals(type)) {
                log.info("🎛️ Voice WS config received: session={}, payload={}", session.getId(), payload);
                Map<String, Object> cfg = (Map<String, Object>) msg.get("config");
                if (cfg != null && cfg.containsKey("language")) {
                    String lang = String.valueOf(cfg.get("language"));
                    updateLanguage(session, lang);
                    SessionContext ctx = sessions.get(session.getId());
                    log.info("✅ Voice WS config accepted: session={}, requestedLanguage={}, effectiveRecognitionLanguage={}",
                            session.getId(),
                            lang,
                            ctx != null ? ctx.language : canonicalRecognitionLanguage(lang));
                } else {
                    log.warn("Rejected voice config for session {}: missing language in {}", session.getId(), cfg);
                }
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
        String configuredRecognitionLanguage = canonicalRecognitionLanguage(ctx.language);
        String lang = commandLanguage(configuredRecognitionLanguage);
        String detectedLang = LanguageDetector.detect(text);
        String correlationId = ctx.correlationId != null ? ctx.correlationId : "no-correlation-id";

        if (detectedLang != null && !detectedLang.equals(lang)) {
            log.warn("Transcript language differs from configured session language: transcript='{}', detected={}, effectiveRecognitionLanguage={}, usingCommandLanguage={}, correlationId={}",
                    text, detectedLang, configuredRecognitionLanguage, lang, correlationId);
        }

        var ruleMatch = ruleBasedVoiceCommandService.match(text, lang);
        if (ruleMatch.isPresent()) {
            handleRuleCommand(ctx, ruleMatch.get(), lang, correlationId);
            return;
        }

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

        // Hybrid voice: pre-recorded .wav when available, TTS fallback
        try {
            String normalizedAction = action != null ? action.toLowerCase().replace("-", "_") : "unknown";
            log.debug("🔊 Resolving voice output: action={}, correlationId={}", normalizedAction, correlationId);
            byte[] audio = voiceOutputService.resolveAndGetAudio(
                    normalizedAction,
                    responseText,
                    lang,
                    languageCode(lang),
                    voiceName(lang));
            if (audio == null || audio.length == 0) {
                log.warn("Voice output returned no audio, correlationId={}", correlationId);
                return;
            }
            log.info("🔊 Voice audio ready {} bytes, correlationId={}", audio.length, correlationId);

            if (ctx.session.isOpen()) {
                ctx.session.sendMessage(new BinaryMessage(audio));
                log.debug("🔊 Voice audio sent, correlationId={}", correlationId);
            }
        } catch (IOException e) {
            log.error("Failed to send voice audio over WS, correlationId={}", correlationId, e);
        } catch (RuntimeException e) {
            log.error("Failed to resolve/synthesize voice audio, correlationId={}", correlationId, e);
        }
    }

    private void handleRuleCommand(SessionContext ctx, VoiceCommandCatalog.Match match, String lang, String correlationId) {
        String action = match.actionName() != null ? match.actionName() : "UNKNOWN";
        String responseText = resolveRuleResponseText(match, lang);

        try {
            var dispatchResult = voiceCommandActionDispatcher.dispatch(match, ctx.userId, correlationId);
            if (dispatchResult.routedAction() != null && !dispatchResult.routedAction().isBlank()) {
                action = dispatchResult.routedAction();
            }

            sendCommandResponse(ctx.session, action, true, responseText, correlationId);

            byte[] audio = voiceOutputService.resolveRuleResponseAudio(
                    match.responseKey(),
                    responseText,
                    lang,
                    languageCode(lang),
                    voiceName(lang));
            sendAudioResponse(ctx.session, audio, correlationId);
        } catch (RuntimeException e) {
            log.error("❌ Error executing rule-based command: id={}, action={}, correlationId={}",
                    match.command().id(), action, correlationId, e);
            String errorText = lang.startsWith("ru")
                    ? "Произошла ошибка при выполнении команды."
                    : "An error occurred while executing the command.";
            sendCommandResponse(ctx.session, action, false, errorText, correlationId);
            byte[] audio = voiceOutputService.resolveRuleResponseAudio(
                    null,
                    errorText,
                    lang,
                    languageCode(lang),
                    voiceName(lang));
            sendAudioResponse(ctx.session, audio, correlationId);
        }
    }

    private String resolveRuleResponseText(VoiceCommandCatalog.Match match, String lang) {
        String responseText = match.responseText(lang);
        if (responseText != null && !responseText.isBlank()) {
            return responseText;
        }
        responseText = wavResponseRegistry.lookupText(match.responseKey(), lang);
        if (responseText != null && !responseText.isBlank()) {
            return responseText;
        }
        return lang.startsWith("ru") ? "Выполняю." : "Executing.";
    }

    private void sendCommandResponse(WebSocketSession session, String action, boolean handled,
                                     String responseText, String correlationId) {
        try {
            sendJsonMessage(session, Map.of(
                    "type", "RESPONSE",
                    "action", action,
                    "handled", handled,
                    "text", responseText,
                    "correlationId", correlationId));
        } catch (RuntimeException e) {
            log.error("Failed to send RESPONSE message, correlationId={}", correlationId, e);
        }
    }

    private void sendAudioResponse(WebSocketSession session, byte[] audio, String correlationId) {
        if (audio == null || audio.length == 0) {
            log.warn("Voice output returned no audio, correlationId={}", correlationId);
            return;
        }

        try {
            log.info("🔊 Voice audio ready {} bytes, correlationId={}", audio.length, correlationId);
            if (session.isOpen()) {
                session.sendMessage(new BinaryMessage(audio));
                log.debug("🔊 Voice audio sent, correlationId={}", correlationId);
            }
        } catch (IOException e) {
            log.error("Failed to send voice audio over WS, correlationId={}", correlationId, e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("Voice WS closed: session={}, status={}", session.getId(), status);
        SessionContext ctx = sessions.remove(session.getId());
        if (ctx != null && ctx.recognitionSession != null) {
            ctx.recognitionSession.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Voice WS transport error: session={}, userId={}",
                session.getId(),
                resolveUserId(session),
                exception);
        super.handleTransportError(session, exception);
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
        boolean textDelivered = false;

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "RESPONSE",
                    "action", "NOTIFY",
                    "handled", true,
                    "text", message,
                    "correlationId", correlationId));
            if (ctx.session.isOpen()) {
                ctx.session.sendMessage(new TextMessage(json));
                textDelivered = true;
            }

            byte[] audio = voiceOutputService.resolveAndGetAudio(
                    "notify",
                    message,
                    lang,
                    languageCode(lang),
                    voiceName(lang));
            if (audio == null || audio.length == 0) {
                log.warn("Voice notification TTS returned no audio, userId={}, correlationId={}",
                        ctx.userId, correlationId);
                return textDelivered;
            }
            if (ctx.session.isOpen()) {
                ctx.session.sendMessage(new BinaryMessage(audio));
            }
            return textDelivered;
        } catch (IOException e) {
            if (textDelivered) {
                log.warn("Voice notification audio delivery degraded after text notify for session {}",
                        ctx.session.getId(), e);
                return true;
            }
            log.error("Failed to send voice notification to session {}", ctx.session.getId(), e);
            return false;
        } catch (RuntimeException e) {
            if (textDelivered) {
                log.warn("Voice notification audio synthesis degraded after text notify for session {}",
                        ctx.session.getId(), e);
                return true;
            }
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

        String previousLanguage = canonicalRecognitionLanguage(ctx.language);
        String effectiveLanguage = canonicalRecognitionLanguage(language);
        log.info("Updating session {} recognition language: previous={}, requested={}, effective={}",
                session.getId(), previousLanguage, language, effectiveLanguage);
        try {
            if (ctx.recognitionSession != null) {
                ctx.recognitionSession.close();
            }
            ctx.language = effectiveLanguage;
            ctx.recognitionSession = tryCreateRecognitionSession(effectiveLanguage);
            if (ctx.recognitionSession == null) {
                sendSttUnavailable(session, effectiveLanguage, ctx.correlationId);
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
        String message = sttUnavailableMessage(lang);
        sendJsonMessage(session, Map.of(
                "type", "RESPONSE",
                "action", "STT_UNAVAILABLE",
                "handled", false,
                "text", message,
                "correlationId", correlationId != null ? correlationId : ""));
        try {
            byte[] audio = voiceOutputService.resolveAndGetAudio(
                    "stt_unavailable",
                    message,
                    lang,
                    languageCode(lang),
                    voiceName(lang));
            if (audio != null && audio.length > 0 && session.isOpen()) {
                session.sendMessage(new BinaryMessage(audio));
            }
        } catch (IOException e) {
            log.error("Failed to send STT unavailable audio over WS, correlationId={}", correlationId, e);
        } catch (RuntimeException e) {
            log.error("Failed to resolve STT unavailable voice output, correlationId={}", correlationId, e);
        }
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

            // Hybrid voice: pre-recorded for stt_timeout when available
            byte[] audio = voiceOutputService.resolveAndGetAudio(
                    "stt_timeout",
                    timeoutResponse,
                    lang,
                    languageCode(lang),
                    voiceName(lang));
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
        return canonicalRecognitionLanguage(lang);
    }

    private String voiceName(String lang) {
        return canonicalRecognitionLanguage(lang).startsWith("en")
                ? "en-US-Wavenet-D"
                : "ru-RU-Wavenet-A";
    }

    private String canonicalRecognitionLanguage(String language) {
        String fallback = defaultLanguage != null ? defaultLanguage : "ru-RU";
        if (language == null || language.isBlank()) {
            return fallback.toLowerCase(Locale.ROOT).startsWith("en") ? "en-US" : "ru-RU";
        }
        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        return normalized.startsWith("en") ? "en-US" : "ru-RU";
    }

    private String commandLanguage(String recognitionLanguage) {
        return canonicalRecognitionLanguage(recognitionLanguage).startsWith("en") ? "en" : "ru";
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
