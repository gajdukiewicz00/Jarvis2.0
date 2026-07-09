package org.jarvis.voicegateway.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.rules.RuleBasedVoiceCommandService;
import org.jarvis.voicegateway.service.LocalIntentExecutionService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final LocalIntentExecutionService localIntentExecutionService;
    private final OrchestratorClient orchestratorClient;
    private final ObjectMapper objectMapper;

    // Store active sessions and their recognition sessions
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    @Value("${jarvis.vosk.default-language:ru-RU}")
    private String defaultLanguage;

    /**
     * How long a STARTED session is allowed to sit idle (connected, no audio
     * bytes streamed yet) before an END with zero audio is treated as a real
     * failure. Below this window, ending without audio is a normal "connected
     * and waiting" outcome (mic not streaming yet, user cancelled quickly,
     * client-side listen-timeout firing early) and must be reported as
     * healthy/waiting rather than a protocol error. Configurable via
     * jarvis.voice.no-audio-timeout-ms (default 8s).
     */
    @Value("${jarvis.voice.no-audio-timeout-ms:8000}")
    private long noAudioTimeoutMs;

    private enum SessionPhase {
        IDLE,
        STARTED,
        STREAMING,
        PROCESSING,
        DONE
    }

    private record CommandResponseOutcome(
            String action,
            String responseText,
            boolean handled,
            boolean recognized,
            boolean actionResolved,
            boolean executorFound,
            boolean executionAttempted,
            boolean executionSucceeded,
            boolean executionFailed,
            String failureReason) {
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = resolveUserId(session);
        String effectiveLanguage = canonicalRecognitionLanguage(defaultLanguage);
        SessionContext ctx = new SessionContext(
                session,
                null,
                effectiveLanguage,
                userId);
        sessions.put(session.getId(), ctx);

        Map<String, Object> sttRuntime = sttService.describeRuntime();
        Map<String, Object> ttsRuntime = ttsService.describeRuntime();
        boolean sttAvailable = Boolean.TRUE.equals(sttRuntime.get("available"));
        boolean ttsAvailable = Boolean.TRUE.equals(ttsRuntime.get("available"));

        log.info("🎙️ Voice WS open: session={}, userId={}, username={}, requestedDefaultLanguage={}, effectiveRecognitionLanguage={}, sttAvailable={}, ttsAvailable={}",
                session.getId(),
                userId,
                firstHeader(session, "X-Username"),
                defaultLanguage,
                ctx.language,
                sttAvailable,
                ttsAvailable);

        sendState(ctx, "CONNECTED");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Received text message: {}", payload);

        try {
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);
            String type = (String) msg.get("type");
            SessionContext ctx = sessions.get(session.getId());
            if (ctx == null) {
                return;
            }

            if ("START".equalsIgnoreCase(type)) {
                String correlationId = (String) msg.get("correlationId");
                Object rawLanguage = msg.get("language");
                String requestedLanguage = rawLanguage != null ? String.valueOf(rawLanguage) : null;
                if (ctx.phase == SessionPhase.STARTED || ctx.phase == SessionPhase.STREAMING || ctx.phase == SessionPhase.PROCESSING) {
                    protocolError(ctx, "DUPLICATE_START", "Voice session already started.");
                    return;
                }
                resetSession(ctx, true);
                ctx.correlationId = correlationId;
                ctx.language = canonicalRecognitionLanguage(requestedLanguage != null ? requestedLanguage : ctx.language);
                ctx.recognitionSession = tryCreateRecognitionSession(ctx.language);
                if (ctx.recognitionSession == null) {
                    ctx.phase = SessionPhase.DONE;
                    sendSttUnavailable(session, ctx.language, correlationId);
                    sendState(ctx, "STT_UNAVAILABLE");
                    return;
                }
                ctx.phase = SessionPhase.STARTED;
                ctx.startedAt = Instant.now();
                log.info("🎤 Voice command started, correlationId={}, session={}, requestedLanguage={}, effectiveRecognitionLanguage={}",
                        correlationId, session.getId(), requestedLanguage, ctx.language);
                sendState(ctx, "STARTED");
            } else if ("CONFIG".equals(type)) {
                log.info("🎛️ Voice WS config received: session={}, payload={}", session.getId(), payload);
                Map<String, Object> cfg = (Map<String, Object>) msg.get("config");
                if (cfg != null && cfg.containsKey("language")) {
                    if (ctx.phase == SessionPhase.STREAMING || ctx.phase == SessionPhase.PROCESSING) {
                        protocolError(ctx, "CONFIG_NOT_ALLOWED", "Cannot change language while audio is streaming.");
                        return;
                    }
                    String lang = String.valueOf(cfg.get("language"));
                    updateLanguage(session, lang);
                    log.info("✅ Voice WS config accepted: session={}, requestedLanguage={}, effectiveRecognitionLanguage={}",
                            session.getId(),
                            lang,
                            ctx != null ? ctx.language : canonicalRecognitionLanguage(lang));
                    sendState(ctx, "CONFIGURED");
                } else {
                    log.warn("Rejected voice config for session {}: missing language in {}", session.getId(), cfg);
                    protocolError(ctx, "CONFIG_INVALID", "Voice config must include language.");
                }
            } else if ("END".equalsIgnoreCase(type)) {
                String correlationId = (String) msg.get("correlationId");
                if (correlationId != null) {
                    ctx.correlationId = correlationId; // Ensure it's set
                }
                if (ctx.phase == SessionPhase.STARTED && ctx.receivedAudioBytes == 0) {
                    handleEndWithoutAudio(ctx, session);
                    return;
                }
                if (ctx.phase != SessionPhase.STREAMING) {
                    // Idempotent END. The server auto-finalizes on silence detection
                    // (STREAMING -> PROCESSING -> DONE), so a client END that races that
                    // finalize — or a duplicate/late END, or an END on a session that
                    // already completed — is benign, NOT a protocol error. Emitting an
                    // ERROR frame here is what surfaced "END is only valid while audio is
                    // streaming" as a bogus assistant response in the desktop. Swallow it
                    // as a stream-lifecycle no-op (logged, no client-visible frame).
                    log.debug("voice.stream.invalid_end_without_active_stream session={} phase={} correlationId={} "
                            + "(idempotent END ignored — no active audio stream; not surfaced to the user)",
                            session.getId(), ctx.phase, correlationId);
                    return;
                }
                log.info("⏹️ End-of-speech received, correlationId={}, session={}",
                        correlationId, session.getId());
                finalizeRecognition(session);
            } else if ("TIMEOUT".equalsIgnoreCase(type)) {
                String correlationId = (String) msg.get("correlationId");
                if (correlationId != null) {
                    ctx.correlationId = correlationId;
                }
                if (ctx.phase != SessionPhase.STARTED && ctx.phase != SessionPhase.STREAMING) {
                    protocolError(ctx, "TIMEOUT_NOT_ALLOWED", "Timeout can only be requested for an active voice session.");
                    return;
                }
                log.info("⏰ STT timeout received, sending timeout phrase, correlationId={}", correlationId);
                handleSttTimeout(session, correlationId);
                resetSession(ctx, true);
                ctx.phase = SessionPhase.DONE;
                sendState(ctx, "TIMEOUT");
            } else {
                protocolError(ctx, "UNKNOWN_MESSAGE_TYPE", "Unsupported voice websocket message type.");
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Invalid WS payload, sessionId={}", session.getId(), e);
            SessionContext ctx = sessions.get(session.getId());
            if (ctx != null) {
                protocolError(ctx, "INVALID_PAYLOAD", "Voice websocket payload must be valid JSON.");
            }
        } catch (IllegalArgumentException e) {
            log.warn("Bad WS input, sessionId={}", session.getId(), e);
            SessionContext ctx = sessions.get(session.getId());
            if (ctx != null) {
                protocolError(ctx, "BAD_REQUEST", e.getMessage());
            }
        } catch (RuntimeException e) {
            log.error("Error parsing text message, sessionId={}", session.getId(), e);
            SessionContext ctx = sessions.get(session.getId());
            if (ctx != null) {
                protocolError(ctx, "INTERNAL_ERROR", "Voice websocket text handling failed.");
            }
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

        if (ctx.phase != SessionPhase.STARTED && ctx.phase != SessionPhase.STREAMING) {
            protocolError(ctx, "AUDIO_BEFORE_START", "Audio chunks require an active START command.");
            return;
        }

        if (ctx.phase == SessionPhase.STARTED) {
            ctx.phase = SessionPhase.STREAMING;
            sendState(ctx, "STREAMING");
        }
        ctx.receivedAudioBytes += data.length;

        // Log every 10th chunk to avoid spam
        int chunkNum = ++ctx.chunkCounter;
        if (chunkNum == 1 || chunkNum % 10 == 0) {
            log.debug("📦 Audio chunk #{}: {} bytes, correlationId={}, session={}",
                    chunkNum, data.length, ctx.correlationId, session.getId());
        }

        StreamingRecognitionSession recognitionSession = ctx.recognitionSession;
        if (recognitionSession == null) {
            ctx.phase = SessionPhase.DONE;
            sendSttUnavailable(session, ctx.language, ctx.correlationId);
            sendState(ctx, "STT_UNAVAILABLE");
            return;
        }

        if (recognitionSession.acceptWaveForm(data, data.length)) {
            String result = recognitionSession.getResult();
            log.info("🔇 Silence detected -> finalizing transcript, correlationId={}", ctx.correlationId);
            ctx.phase = SessionPhase.PROCESSING;
            sendState(ctx, "PROCESSING");
            processRecognitionResult(ctx, result, true);
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
            if (json == null || json.isEmpty()) {
                if (isFinal) {
                    protocolError(ctx, "NO_SPEECH_RECOGNIZED", noSpeechMessage(ctx.language));
                    resetSession(ctx, true);
                    ctx.phase = SessionPhase.DONE;
                    sendState(ctx, "DONE");
                }
                return;
            }

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
            } else if (isFinal) {
                protocolError(ctx, "NO_SPEECH_RECOGNIZED", noSpeechMessage(ctx.language));
                resetSession(ctx, true);
                ctx.phase = SessionPhase.DONE;
                sendState(ctx, "DONE");
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
            handleRuleCommand(ctx, ruleMatch.get(), text, lang, correlationId);
            return;
        }

        String responseText = null;
        String action = "UNKNOWN";
        boolean recognized = text != null && !text.isBlank();
        boolean actionResolved = false;
        boolean executorFound = false;
        boolean executionAttempted = false;
        boolean executionSucceeded = false;
        boolean executionFailed = false;
        String failureReason = null;
        String outcomeSource = "intent";

        try {
            log.info("🎯 Processing command: '{}', correlationId={}, detectedLang={}", text, correlationId, lang);

            IntentResult intent = intentService.handle(IntentRequest.builder()
                    .text(text)
                    .language(lang)
                    .sessionId(ctx.session.getId())
                    .correlationId(correlationId)
                    .build());

            action = intent.getAction() != null ? intent.getAction() : "UNKNOWN";
            responseText = intent.getResponse();
            actionResolved = intent.isHandled() && !"UNKNOWN".equalsIgnoreCase(action);

            log.info("🔍 Intent detected: action={}, actionResolved={}, params={}, correlationId={}",
                    action, actionResolved, intent.getParameters(), correlationId);

            String orchestratorAction = actionResolved ? action : "fallback";
            log.info("📤 Sending intent to orchestrator: action={}, params={}, correlationId={}",
                    orchestratorAction, intent.getParameters(), correlationId);
            try {
                OrchestratorClient.IntentExecutionResult orchestratorResult = orchestratorClient.sendIntentDetailed(
                        orchestratorAction,
                        intent.getParameters(),
                        lang,
                        correlationId,
                        text,
                        ctx.userId); // Pass original text and user context for downstream routing
                log.info(
                        "📥 Orchestrator response: response='{}', executorFound={}, executionAttempted={}, executionSucceeded={}, executionFailed={}, failureReason={}, correlationId={}",
                        orchestratorResult != null ? orchestratorResult.responseText() : null,
                        orchestratorResult != null && orchestratorResult.executorFound(),
                        orchestratorResult != null && orchestratorResult.executionAttempted(),
                        orchestratorResult != null && orchestratorResult.executionSucceeded(),
                        orchestratorResult != null && orchestratorResult.executionFailed(),
                        orchestratorResult != null ? orchestratorResult.failureReason() : null,
                        correlationId);

                if (orchestratorResult != null) {
                    if (orchestratorResult.responseText() != null && !orchestratorResult.responseText().isBlank()) {
                        responseText = orchestratorResult.responseText();
                    }
                    executorFound = orchestratorResult.executorFound();
                    executionAttempted = orchestratorResult.executionAttempted();
                    executionSucceeded = orchestratorResult.executionSucceeded();
                    executionFailed = orchestratorResult.executionFailed();
                    failureReason = orchestratorResult.failureReason();
                } else if (actionResolved) {
                    executionFailed = true;
                    failureReason = "Orchestrator returned an empty response";
                }
            } catch (RuntimeException e) {
                failureReason = e.getMessage();
                log.error("❌ Failed to call orchestrator, correlationId={}, error={}", correlationId, e.getMessage());
                if (actionResolved) {
                    LocalIntentExecutionService.ExecutionResult fallbackResult = localIntentExecutionService.execute(
                            action,
                            intent.getParameters(),
                            lang,
                            correlationId,
                            ctx.userId);
                    outcomeSource = "local_fallback";
                    action = fallbackResult.action() != null ? fallbackResult.action() : action;
                    if (fallbackResult.responseText() != null && !fallbackResult.responseText().isBlank()) {
                        responseText = fallbackResult.responseText();
                    }
                    actionResolved = fallbackResult.actionResolved();
                    executorFound = fallbackResult.executorFound();
                    executionAttempted = fallbackResult.executionAttempted();
                    executionSucceeded = fallbackResult.executionSucceeded();
                    executionFailed = fallbackResult.executionFailed();
                    failureReason = fallbackResult.failureReason();
                } else {
                    executionFailed = true;
                }
            }
        } catch (RuntimeException e) {
            failureReason = e.getMessage();
            if (actionResolved) {
                executionFailed = true;
            }
            log.error("❌ Error in handleCommand, correlationId={}", correlationId, e);
            responseText = lang.startsWith("ru")
                    ? "Произошла ошибка при обработке команды."
                    : "An error occurred while processing the command.";
        }

        boolean handled = isHandled(actionResolved, executionAttempted, executionSucceeded, executionFailed, failureReason);
        if (shouldUseFailureResponse(actionResolved, executionSucceeded, executionFailed, failureReason)) {
            responseText = commandFailureMessage(lang, failureReason);
        }
        if (responseText == null || responseText.isBlank()) {
            responseText = handled
                    ? (lang.startsWith("ru") ? "Команда обработана." : "Command processed.")
                    : commandFailureMessage(lang, failureReason);
        }

        CommandResponseOutcome outcome = new CommandResponseOutcome(
                action,
                responseText,
                handled,
                recognized,
                actionResolved,
                executorFound,
                executionAttempted,
                executionSucceeded,
                executionFailed,
                failureReason);
        logCommandOutcome(outcomeSource, action, outcome, correlationId);

        // Send response to client - wrapped in try-catch to prevent WS close
        sendCommandResponse(ctx.session, outcome, correlationId);

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
        } finally {
            resetSession(ctx, true);
            ctx.phase = SessionPhase.DONE;
            sendState(ctx, "DONE");
        }
    }

    private void handleRuleCommand(
            SessionContext ctx, VoiceCommandCatalog.Match match, String recognizedText, String lang,
            String correlationId) {
        String action = match.actionName() != null ? match.actionName() : "UNKNOWN";
        String matchedRuleId = match.command() != null ? match.command().id() : "unknown";
        String responseText = resolveRuleResponseText(match, lang);

        try {
            var dispatchResult = voiceCommandActionDispatcher.dispatch(match, ctx.userId, correlationId);
            if (dispatchResult.routedAction() != null && !dispatchResult.routedAction().isBlank()) {
                action = dispatchResult.routedAction();
            }

            if (shouldUseFailureResponse(
                    dispatchResult.actionResolved(),
                    dispatchResult.executionSucceeded(),
                    dispatchResult.executionFailed(),
                    dispatchResult.failureReason())) {
                responseText = actionFailureMessage(lang, match, action, dispatchResult.failureReason());
            } else if (dispatchResult.responseTextOverride() != null
                    && !dispatchResult.responseTextOverride().isBlank()) {
                // Dynamic spoken text (e.g. real planner summary) overrides the static phrase.
                responseText = dispatchResult.responseTextOverride();
            }

            CommandResponseOutcome outcome = new CommandResponseOutcome(
                    action,
                    responseText,
                    isHandled(
                            dispatchResult.actionResolved(),
                            dispatchResult.executionAttempted(),
                            dispatchResult.executionSucceeded(),
                            dispatchResult.executionFailed(),
                            dispatchResult.failureReason()),
                    true,
                    dispatchResult.actionResolved(),
                    dispatchResult.executorFound(),
                    dispatchResult.executionAttempted(),
                    dispatchResult.executionSucceeded(),
                    dispatchResult.executionFailed(),
                    dispatchResult.failureReason());
            logCommandOutcome("rule", action, outcome, correlationId);
            log.info(
                    "🧾 Voice action dispatch: recognizedText='{}', matchedRuleId={}, intent={}, actionType={}, targetService={}, userId={}, correlationId={}, status={}, userMessage='{}'",
                    recognizedText,
                    matchedRuleId,
                    action,
                    action,
                    match.action() != null ? match.action().target() : "INTERNAL",
                    maskUserId(ctx.userId),
                    correlationId,
                    commandStatus(outcome),
                    responseText);
            sendCommandResponse(ctx.session, outcome, correlationId);

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
            String errorText = actionFailureMessage(lang, match, action, e.getMessage());
            CommandResponseOutcome outcome = new CommandResponseOutcome(
                    action,
                    errorText,
                    false,
                    true,
                    true,
                    false,
                    false,
                    false,
                    true,
                    e.getMessage());
            logCommandOutcome("rule", action, outcome, correlationId);
            sendCommandResponse(ctx.session, outcome, correlationId);
            byte[] audio = voiceOutputService.resolveRuleResponseAudio(
                    null,
                    errorText,
                    lang,
                    languageCode(lang),
                    voiceName(lang));
            sendAudioResponse(ctx.session, audio, correlationId);
        } finally {
            resetSession(ctx, true);
            ctx.phase = SessionPhase.DONE;
            sendState(ctx, "DONE");
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

    private boolean isHandled(
            boolean actionResolved,
            boolean executionAttempted,
            boolean executionSucceeded,
            boolean executionFailed,
            String failureReason) {
        if (!actionResolved) {
            return false;
        }
        if (executionFailed) {
            return false;
        }
        if (failureReason != null && !failureReason.isBlank() && !executionSucceeded) {
            return false;
        }
        if (executionAttempted) {
            return executionSucceeded;
        }
        return true;
    }

    private boolean shouldUseFailureResponse(
            boolean actionResolved,
            boolean executionSucceeded,
            boolean executionFailed,
            String failureReason) {
        return actionResolved
                && !executionSucceeded
                && (executionFailed || (failureReason != null && !failureReason.isBlank()));
    }

    private String commandFailureMessage(String lang, String failureReason) {
        boolean ru = lang.startsWith("ru");
        if (failureReason != null) {
            String reason = failureReason.toUpperCase(Locale.ROOT);
            if (isConfirmationReason(reason)) {
                return ru
                        ? "Сэр, это действие требует подтверждения."
                        : "Sir, this action requires confirmation.";
            }
            if (reason.contains("CAPABILITY_UNAVAILABLE") || reason.contains("UNSUPPORTED")) {
                return capabilityUnavailableMessage(lang);
            }
            if (reason.contains("NO_ACTIVE_PLAYER") || reason.contains("NO PLAYERS FOUND")) {
                return ru
                        ? "Сэр, сейчас нет активного плеера. Откройте Spotify или YouTube Music, либо скажите «включи музыку»."
                        : "Sir, there is no active media player. Open Spotify or YouTube Music, or say 'turn on music'.";
            }
            if (reason.contains("PLAYERCTL_NOT_INSTALLED")) {
                return ru
                        ? "Не удалось управлять плеером: playerctl не установлен, сэр."
                        : "Media control failed: playerctl is not installed, sir.";
            }
            if (reason.contains("APP_NOT_FOUND") || reason.contains("APP ALIAS")
                    || reason.contains("UNKNOWN ACTION") || reason.contains("NO SUCH")) {
                return ru
                        ? "Сэр, не нашёл это приложение на компьютере."
                        : "Sir, I couldn't find that application on the computer.";
            }
            if (reason.contains("ENDPOINT_UNREACHABLE") || reason.contains("CONNECTION REFUSED")
                    || reason.contains("I/O ERROR") || reason.contains("CONNECT TO")) {
                return ru
                        ? "Сэр, не удалось связаться со службой управления компьютером."
                        : "Sir, I couldn't reach the PC-control service.";
            }
            if (reason.contains("HTTP_401") || reason.contains("HTTP_403")
                    || reason.contains("PERMISSION") || reason.contains("DENIED")
                    || reason.contains("AUTH")) {
                return ru
                        ? "Не удалось выполнить: отказано в доступе, сэр."
                        : "Command failed: access denied, sir.";
            }
            if (reason.contains("INVALID_PAYLOAD") || reason.contains("BAD REQUEST")) {
                return ru
                        ? "Сэр, команда пришла с неверными параметрами."
                        : "Sir, the command had invalid parameters.";
            }
            if (reason.contains("ACK_TIMEOUT") || reason.contains("DID NOT ACKNOWLEDGE")
                    || reason.contains("TIMED OUT") || reason.contains("TIMEOUT")) {
                return ru
                        ? "Сэр, компьютер не ответил вовремя."
                        : "Sir, the computer did not respond in time.";
            }
            if (reason.contains("NO_CLIENTS") || reason.contains("USER_NOT_CONNECTED")
                    || reason.contains("NOT_CONNECTED") || reason.contains("NOT CONNECTED")
                    || reason.contains("NO IDENTIFIED DESKTOP") || reason.contains("SESSION_NOT_FOUND")
                    || reason.contains("SESSION IS NOT CONNECTED")) {
                return ru
                        ? "Сэр, приложение на компьютере не подключено — не могу выполнить действие."
                        : "Sir, the desktop app is not connected, so I can't run that action.";
            }
        }
        return ru
                ? "Не удалось выполнить команду."
                : "I couldn't execute that command.";
    }

    /**
     * Builds a failure message that names BOTH the attempted action and the failing
     * subsystem (e.g. "Не удалось открыть Telegram: PC-control недоступен, сэр."), so the
     * user never hears a bare generic error. Falls back to {@link #commandFailureMessage}
     * for actions without a specific verb or for non-Russian output.
     */
    private String actionFailureMessage(
            String lang, VoiceCommandCatalog.Match match, String action, String failureReason) {
        boolean ru = lang.startsWith("ru");
        String upper = failureReason == null ? "" : failureReason.toUpperCase(Locale.ROOT);
        if (isConfirmationReason(upper)) {
            return ru ? "Сэр, это действие требует подтверждения." : "Sir, this action requires confirmation.";
        }
        if (!ru) {
            return commandFailureMessage(lang, failureReason);
        }
        String verb = failureVerb(match, action);
        if (verb == null) {
            return commandFailureMessage(lang, failureReason);
        }
        return "Не удалось " + verb + ": " + failureCause(match, action, upper) + ", сэр.";
    }

    private String failureVerb(VoiceCommandCatalog.Match match, String action) {
        VoiceCommandCatalog.ActionTarget target = match.action() != null ? match.action().target() : null;
        if (target == VoiceCommandCatalog.ActionTarget.PLANNER) {
            return "получить план";
        }
        String name = action == null ? "" : action.toUpperCase(Locale.ROOT);
        return switch (name) {
            case "OPEN_APP" -> "открыть " + appLabel(actionParam(match, "app"));
            case "OPEN_URL" -> "включить музыку";
            case "VOLUME_UP" -> "сделать громче";
            case "VOLUME_DOWN" -> "убавить громкость";
            case "MUTE" -> "выключить звук";
            case "UNMUTE" -> "включить звук";
            case "NEXT", "NEXT_TRACK" -> "переключить трек";
            case "PREV", "PREVIOUS" -> "вернуть трек";
            case "PAUSE" -> "поставить на паузу";
            case "MINIMIZE_ALL_WINDOWS", "MINIMIZE_ALL", "SHOW_DESKTOP" -> "свернуть окна";
            default -> null;
        };
    }

    private String appLabel(String app) {
        if (app == null || app.isBlank()) {
            return "приложение";
        }
        return switch (app.toLowerCase(Locale.ROOT)) {
            case "telegram", "telegram-desktop" -> "Telegram";
            case "files", "file-manager", "nautilus", "dolphin" -> "файлы";
            case "vscode", "vs code", "code" -> "VS Code";
            case "terminal", "konsole" -> "терминал";
            case "browser", "firefox" -> "браузер";
            case "chrome", "google-chrome" -> "Chrome";
            default -> app;
        };
    }

    private String failureCause(VoiceCommandCatalog.Match match, String action, String upper) {
        VoiceCommandCatalog.ActionTarget target = match.action() != null ? match.action().target() : null;
        boolean unreachable = upper.contains("ENDPOINT_UNREACHABLE") || upper.contains("CONNECTION REFUSED")
                || upper.contains("I/O ERROR") || upper.contains("CONNECT TO") || upper.contains("PLANNER_UNAVAILABLE");
        if (target == VoiceCommandCatalog.ActionTarget.PLANNER) {
            return unreachable ? "planner-service недоступен" : "planner-service вернул ошибку";
        }
        boolean isWindows = "MINIMIZE_ALL_WINDOWS".equalsIgnoreCase(action) || "SHOW_DESKTOP".equalsIgnoreCase(action)
                || "HOTKEY".equalsIgnoreCase(action);
        if (unreachable) {
            return isWindows ? "канал управления недоступен" : "PC-control недоступен";
        }
        if (upper.contains("NO_CLIENTS") || upper.contains("USER_NOT_CONNECTED") || upper.contains("NOT CONNECTED")
                || upper.contains("NOT_CONNECTED") || upper.contains("NO IDENTIFIED DESKTOP")
                || upper.contains("SESSION_NOT_FOUND")) {
            return "приложение на компьютере не подключено";
        }
        if (upper.contains("ACK_TIMEOUT") || upper.contains("DID NOT ACKNOWLEDGE")
                || upper.contains("TIMED OUT") || upper.contains("TIMEOUT")) {
            return "компьютер не ответил вовремя";
        }
        if (upper.contains("HTTP_401") || upper.contains("HTTP_403") || upper.contains("PERMISSION")
                || upper.contains("DENIED") || upper.contains("AUTH")) {
            return "отказано в доступе";
        }
        if (upper.contains("NO_ACTIVE_PLAYER") || upper.contains("NO PLAYERS")) {
            return "нет активного плеера";
        }
        if (upper.contains("PLAYERCTL_NOT_INSTALLED")) {
            return "playerctl не установлен";
        }
        if (upper.contains("INVALID_PAYLOAD") || upper.contains("BAD REQUEST")) {
            return "неверные параметры";
        }
        if ("OPEN_APP".equalsIgnoreCase(action)
                && (upper.contains("APP_NOT_FOUND") || upper.contains("UNKNOWN ACTION") || upper.contains("NO SUCH")
                        || upper.contains("CANNOT RUN") || upper.contains("COULD NOT OPEN") || upper.contains("OPEN APP"))) {
            String app = actionParam(match, "app");
            return "приложение " + (app != null ? app : "") + " не найдено";
        }
        return "PC-control вернул ошибку";
    }

    private String actionParam(VoiceCommandCatalog.Match match, String key) {
        Map<String, Object> params = match.parameters();
        if (params == null) {
            return null;
        }
        Object value = params.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private String maskUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return "<none>";
        }
        if (userId.length() <= 2) {
            return "***";
        }
        return userId.charAt(0) + "***" + userId.charAt(userId.length() - 1);
    }

    private boolean isConfirmationReason(String upperReason) {
        return upperReason != null
                && (upperReason.contains("REQUIRES_CONFIRMATION")
                        || upperReason.contains("REQUIRE_CONFIRM")
                        || upperReason.contains("CONFIRMATION REQUIRED")
                        || upperReason.contains("CONFIRM=TRUE"));
    }

    private String commandStatus(CommandResponseOutcome outcome) {
        String reason = outcome.failureReason();
        if (reason != null) {
            String upper = reason.toUpperCase(Locale.ROOT);
            if (isConfirmationReason(upper)) {
                return "REQUIRES_CONFIRMATION";
            }
            if (!outcome.handled()
                    && (upper.contains("UNSUPPORTED") || upper.contains("NOT_SUPPORTED")
                            || upper.contains("CAPABILITY_UNAVAILABLE"))) {
                return "NOT_SUPPORTED";
            }
        }
        if (outcome.handled()) {
            return "SUCCESS";
        }
        return "FAILED";
    }

    private void logCommandOutcome(String source, String action, CommandResponseOutcome outcome, String correlationId) {
        log.info(
                "📣 Voice command outcome: source={}, action={}, handled={}, recognized={}, actionResolved={}, executorFound={}, executionAttempted={}, executionSucceeded={}, executionFailed={}, failureReason={}, correlationId={}",
                source,
                action,
                outcome.handled(),
                outcome.recognized(),
                outcome.actionResolved(),
                outcome.executorFound(),
                outcome.executionAttempted(),
                outcome.executionSucceeded(),
                outcome.executionFailed(),
                outcome.failureReason(),
                correlationId);
    }

    private void sendCommandResponse(WebSocketSession session, CommandResponseOutcome outcome, String correlationId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "RESPONSE");
            payload.put("action", outcome.action());
            payload.put("handled", outcome.handled());
            payload.put("recognized", outcome.recognized());
            payload.put("actionResolved", outcome.actionResolved());
            payload.put("executorFound", outcome.executorFound());
            payload.put("executionAttempted", outcome.executionAttempted());
            payload.put("executionSucceeded", outcome.executionSucceeded());
            payload.put("executionFailed", outcome.executionFailed());
            payload.put("text", outcome.responseText());
            // P0.5 structured action result: UI shows userMessage; logs keep debugReason.
            payload.put("status", commandStatus(outcome));
            payload.put("userMessage", outcome.responseText());
            payload.put("correlationId", correlationId);
            if (outcome.failureReason() != null && !outcome.failureReason().isBlank()) {
                payload.put("failureReason", outcome.failureReason());
                payload.put("failureCode", reasonCode(outcome.failureReason()));
                payload.put("debugReason", outcome.failureReason());
            }
            sendJsonMessage(session, payload);
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
        if (ctx != null) {
            if (ctx.phase == SessionPhase.STARTED || ctx.phase == SessionPhase.STREAMING || ctx.phase == SessionPhase.PROCESSING) {
                log.warn("Voice WS session ended mid-command: session={}, phase={}, correlationId={}, reasonCode=CLIENT_DISCONNECTED",
                        session.getId(), ctx.phase, ctx.correlationId);
            }
            resetSession(ctx, true);
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
            ctx.language = effectiveLanguage;
            if (ctx.phase == SessionPhase.STARTED) {
                resetSession(ctx, false);
                ctx.recognitionSession = tryCreateRecognitionSession(effectiveLanguage);
                if (ctx.recognitionSession == null) {
                    ctx.phase = SessionPhase.DONE;
                    sendSttUnavailable(session, effectiveLanguage, ctx.correlationId);
                    sendState(ctx, "STT_UNAVAILABLE");
                }
            }
        } catch (RuntimeException e) {
            log.error("Failed to update language for session {}", session.getId(), e);
            protocolError(ctx, "LANGUAGE_UPDATE_FAILED", "Failed to apply the requested language.");
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

    /**
     * Client sent END while the session was STARTED but never streamed any
     * audio bytes. This is common and expected while the session is still
     * within the configured no-audio grace window (mic capture hasn't
     * started streaming yet, the user cancelled almost immediately, or a
     * client-side listen-timeout fired) — the socket itself is healthy and
     * simply idle/waiting, so it must be reported as such and not as a
     * protocol error. Only once the grace window has fully elapsed with zero
     * audio do we treat the session as a genuine no-audio failure.
     */
    private void handleEndWithoutAudio(SessionContext ctx, WebSocketSession session) {
        long elapsedMs = ctx.startedAt != null
                ? Duration.between(ctx.startedAt, Instant.now()).toMillis()
                : Long.MAX_VALUE;
        boolean withinGraceWindow = elapsedMs < noAudioTimeoutMs;
        if (withinGraceWindow) {
            log.info("🕒 Voice WS idle session ended within no-audio grace window (healthy/waiting): session={}, elapsedMs={}, windowMs={}, correlationId={}",
                    session.getId(), elapsedMs, noAudioTimeoutMs, ctx.correlationId);
            resetSession(ctx, true);
            ctx.phase = SessionPhase.DONE;
            sendState(ctx, "WAITING_FOR_AUDIO");
            return;
        }
        log.warn("Voice WS no-audio grace window elapsed: session={}, elapsedMs={}, windowMs={}, correlationId={}",
                session.getId(), elapsedMs, noAudioTimeoutMs, ctx.correlationId);
        protocolError(ctx, "NO_AUDIO_RECEIVED", "Voice session ended before any audio was received.");
        resetSession(ctx, true);
        ctx.phase = SessionPhase.DONE;
        sendState(ctx, "DONE");
    }

    private void finalizeRecognition(WebSocketSession session) {
        SessionContext ctx = sessions.get(session.getId());
        if (ctx == null || ctx.recognitionSession == null) {
            log.warn("Finalize called but no session context/recognizer for {}", session.getId());
            if (ctx != null) {
                protocolError(ctx, "RECOGNIZER_UNAVAILABLE", sttUnavailableMessage(ctx.language));
                ctx.phase = SessionPhase.DONE;
                sendState(ctx, "DONE");
            }
            return;
        }
        log.info("Finalizing recognition (END marker) for session {}", session.getId());
        ctx.phase = SessionPhase.PROCESSING;
        sendState(ctx, "PROCESSING");
        String result = ctx.recognitionSession.getResult();
        processRecognitionResult(ctx, result, true);
    }

    private void sendSttUnavailable(WebSocketSession session, String language, String correlationId) {
        String lang = language != null ? language : defaultLanguage;
        String message = sttUnavailableMessage(lang);
        sendErrorFrame(session, "STT_UNAVAILABLE", message, correlationId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "RESPONSE");
        payload.put("action", "STT_UNAVAILABLE");
        payload.put("handled", false);
        payload.put("text", message);
        payload.put("correlationId", correlationId != null ? correlationId : "");
        payload.put("failureCode", "STT_UNAVAILABLE");
        sendJsonMessage(session, payload);
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

        try {
            String timeoutResponse = lang.startsWith("en")
                    ? "Sir, I couldn't hear you well."
                    : "Сэр, я вас плохо расслышал.";

            log.info("⏰ Timeout response: '{}', correlationId={}", timeoutResponse, correlationId);

            sendErrorFrame(session, "TIMEOUT", timeoutResponse, correlationId);
            sendJsonMessage(session, Map.of(
                    "type", "RESPONSE",
                    "action", "STT_TIMEOUT",
                    "handled", true,
                    "text", timeoutResponse,
                    "correlationId", correlationId != null ? correlationId : "",
                    "failureCode", "TIMEOUT"));

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

    private String capabilityUnavailableMessage(String lang) {
        return lang.startsWith("ru")
                ? "Эта возможность сейчас недоступна."
                : "That capability is unavailable right now.";
    }

    private String noSpeechMessage(String lang) {
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("en")
                ? "No speech was recognized in this voice session."
                : "В этой голосовой сессии не удалось распознать речь.";
    }

    private void protocolError(SessionContext ctx, String code, String message) {
        if (ctx == null) {
            return;
        }
        log.warn("Voice WS protocol error: session={}, phase={}, correlationId={}, code={}, message={}",
                ctx.session.getId(), ctx.phase, ctx.correlationId, code, message);
        ctx.lastErrorCode = code;
        sendErrorFrame(ctx.session, code, message, ctx.correlationId);
    }

    private void sendErrorFrame(WebSocketSession session, String code, String message, String correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ERROR");
        payload.put("code", code);
        payload.put("message", message);
        if (correlationId != null) {
            payload.put("correlationId", correlationId);
        }
        sendJsonMessage(session, payload);
    }

    private void sendState(SessionContext ctx, String state) {
        if (ctx == null) {
            return;
        }
        Map<String, Object> sttRuntime = sttService.describeRuntime();
        Map<String, Object> ttsRuntime = ttsService.describeRuntime();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "STATE");
        payload.put("state", state);
        payload.put("language", ctx.language);
        payload.put("phase", ctx.phase.name());
        payload.put("sttAvailable", Boolean.TRUE.equals(sttRuntime.get("available")));
        payload.put("ttsAvailable", Boolean.TRUE.equals(ttsRuntime.get("available")));
        payload.put("ttsStatus", String.valueOf(ttsRuntime.getOrDefault("status", "unknown")));
        Object ttsReason = ttsRuntime.get("reason");
        if (ttsReason != null) {
            payload.put("ttsReason", String.valueOf(ttsReason));
        }
        if (ctx.lastErrorCode != null) {
            payload.put("lastErrorCode", ctx.lastErrorCode);
        }
        sendJsonMessage(ctx.session, payload);
    }

    private void resetSession(SessionContext ctx, boolean clearCorrelationId) {
        if (ctx == null) {
            return;
        }
        if (ctx.recognitionSession != null) {
            ctx.recognitionSession.close();
            ctx.recognitionSession = null;
        }
        ctx.chunkCounter = 0;
        ctx.receivedAudioBytes = 0;
        if (clearCorrelationId) {
            ctx.correlationId = null;
        }
    }

    private String reasonCode(String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            return null;
        }
        int separatorIndex = failureReason.indexOf(':');
        return separatorIndex > 0 ? failureReason.substring(0, separatorIndex).trim() : failureReason;
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
        SessionPhase phase = SessionPhase.IDLE;
        int chunkCounter;
        long receivedAudioBytes;
        String lastErrorCode;
        Instant startedAt;

        SessionContext(WebSocketSession session, StreamingRecognitionSession recognitionSession, String language,
                String userId) {
            this.session = session;
            this.recognitionSession = recognitionSession;
            this.language = language;
            this.userId = userId;
        }
    }
}
