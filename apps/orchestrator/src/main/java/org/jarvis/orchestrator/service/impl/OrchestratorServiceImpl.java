package org.jarvis.orchestrator.service.impl;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.client.ApiGatewayPcClient;
import org.jarvis.orchestrator.client.NlpClient;
import org.jarvis.orchestrator.client.PcControlClient;
import org.jarvis.orchestrator.client.SmartHomeClient;
import org.jarvis.orchestrator.config.OrchestratorExecutorProperties;
import org.jarvis.orchestrator.dto.IntentExecutionResult;
import org.jarvis.orchestrator.phrases.JarvisPhraseProvider;
import org.jarvis.orchestrator.phrases.Language;
import org.jarvis.orchestrator.phrases.LanguageDetector;
import org.jarvis.orchestrator.phrases.PhraseContext;
import org.jarvis.orchestrator.resilience.RetryWithBackoff;
import org.jarvis.orchestrator.resilience.SimpleCircuitBreaker;
import org.jarvis.orchestrator.service.OrchestratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrator service implementation with Jarvis cinematic phrase system.
 *
 * Uses {@link JarvisPhraseProvider} for all responses, automatically detecting
 * language from the user's command or using the provided language parameter.
 *
 * LLM integration has circuit breaker pattern:
 * - Feature flag: JARVIS_LLM_ENABLED (default: false)
 * - Timeout: JARVIS_LLM_TIMEOUT_SECONDS (default: 10)
 * - Circuit breaker: after N consecutive failures, LLM is disabled for M seconds
 *
 * NLP calls (analyze) have their own resilience: bounded retry with backoff
 * (analyze is a pure read, safe to retry) plus a circuit breaker so a dead
 * nlp-service degrades to the existing LLM/rule-based fallback phrase instead
 * of failing the whole request with a 500.
 */
@Slf4j
@Service
public class OrchestratorServiceImpl implements OrchestratorService {

    private final NlpClient nlpClient;
    private final PcControlClient pcControlClient;
    private final ApiGatewayPcClient apiGatewayPcClient;
    private final JarvisPhraseProvider phraseProvider;
    private final org.jarvis.orchestrator.client.LlmServiceClient llmClient;
    private final SmartHomeClient smartHomeClient;

    @Value("${api-gateway.url:${API_GATEWAY_URL:http://api-gateway:8080}}")
    private String apiGatewayUrl = "http://api-gateway:8080";

    @Value("${jarvis.nlp.url:${NLP_SERVICE_URL:http://nlp-service:8082}}")
    private String nlpUrl = "http://nlp-service:8082";

    /**
     * Zone used for get_time/get_date intents. The JVM default zone
     * (ZoneId.systemDefault()) depends on the container/host TZ configuration,
     * which is not guaranteed to match the user's actual local zone, so this is
     * bound to an explicit, configurable property instead of relying on it.
     */
    @Value("${jarvis.timezone:Europe/Moscow}")
    private String timezone = "Europe/Moscow";

    // LLM Feature flag and configuration
    @Value("${jarvis.llm.enabled:false}")
    private boolean llmEnabled;

    @Value("${jarvis.llm.timeout-seconds:10}")
    private int llmTimeoutSeconds;

    @Value("${jarvis.llm.circuit-breaker.failure-threshold:3}")
    private int circuitBreakerFailureThreshold;

    @Value("${jarvis.llm.circuit-breaker.reset-timeout-seconds:60}")
    private int circuitBreakerResetTimeoutSeconds;

    // Circuit breaker state - shared SimpleCircuitBreaker (same as nlpCircuitBreaker below),
    // which provides an explicit half-open, single-trial-on-reopen state instead of a raw
    // timestamp comparison that lets every concurrent caller through the instant cooldown elapses.
    private final SimpleCircuitBreaker llmCircuitBreaker = new SimpleCircuitBreaker();

    // NLP call resilience: bounded retry with backoff + its own circuit breaker.
    @Value("${jarvis.nlp.retry.max-attempts:2}")
    private int nlpRetryMaxAttempts = 2;

    @Value("${jarvis.nlp.retry.initial-backoff-ms:200}")
    private long nlpRetryInitialBackoffMs = 200;

    @Value("${jarvis.nlp.circuit-breaker.failure-threshold:3}")
    private int nlpCircuitBreakerFailureThreshold = 3;

    @Value("${jarvis.nlp.circuit-breaker.reset-timeout-seconds:30}")
    private int nlpCircuitBreakerResetTimeoutSeconds = 30;

    private final SimpleCircuitBreaker nlpCircuitBreaker = new SimpleCircuitBreaker();

    // Executor for async LLM calls with timeout (bounded queue + explicit rejection policy)
    private final ThreadPoolExecutor llmExecutor;
    private final OrchestratorExecutorProperties executorProperties;
    private final AtomicLong rejectedLlmTasks = new AtomicLong(0);
    private final ThreadLocal<ExecutionMetadata> executionMetadata = ThreadLocal.withInitial(ExecutionMetadata::none);

    private record ExecutionMetadata(
            boolean executorFound,
            boolean executionAttempted,
            boolean executionSucceeded,
            String failureReason) {
        private static ExecutionMetadata none() {
            return new ExecutionMetadata(false, false, false, null);
        }

        private static ExecutionMetadata success() {
            return new ExecutionMetadata(true, true, true, null);
        }

        private static ExecutionMetadata failure(boolean executorFound, boolean executionAttempted, String failureReason) {
            return new ExecutionMetadata(executorFound, executionAttempted, false, failureReason);
        }
    }

    private record PcDispatchResult(
            boolean executorFound,
            boolean executionAttempted,
            boolean executionSucceeded,
            String failureReason,
            Map<String, Object> rawResponse) {
    }

    private static final class PcActionDispatchException extends RuntimeException {
        private final PcDispatchResult dispatchResult;

        private PcActionDispatchException(String message, PcDispatchResult dispatchResult) {
            super(message);
            this.dispatchResult = dispatchResult;
        }

        private PcDispatchResult dispatchResult() {
            return dispatchResult;
        }
    }

    public OrchestratorServiceImpl(
            NlpClient nlpClient,
            PcControlClient pcControlClient,
            ApiGatewayPcClient apiGatewayPcClient,
            JarvisPhraseProvider phraseProvider,
            org.jarvis.orchestrator.client.LlmServiceClient llmClient,
            SmartHomeClient smartHomeClient,
            OrchestratorExecutorProperties executorProperties) {
        this.nlpClient = nlpClient;
        this.pcControlClient = pcControlClient;
        this.apiGatewayPcClient = apiGatewayPcClient;
        this.phraseProvider = phraseProvider;
        this.llmClient = llmClient;
        this.smartHomeClient = smartHomeClient;
        this.executorProperties = executorProperties;
        this.llmExecutor = createLlmExecutor(executorProperties);
    }

    public String processText(String text, String language, String correlationId) {
        return processText(text, language, correlationId, null);
    }

    @Override
    public IntentExecutionResult processTextDetailed(String text, String language, String correlationId, String userId) {
        executionMetadata.set(ExecutionMetadata.none());
        try {
            String responseText = processText(text, language, correlationId, userId);
            ExecutionMetadata metadata = executionMetadata.get();
            return new IntentExecutionResult(
                    responseText,
                    metadata.executorFound(),
                    metadata.executionAttempted(),
                    metadata.executionSucceeded(),
                    isExecutionFailure(metadata),
                    metadata.failureReason());
        } finally {
            executionMetadata.remove();
        }
    }

    @Override
    public String processText(String text, String language, String correlationId, String userId) {
        log.info("📝 Processing text: '{}', lang={}, correlationId={}", text, language, correlationId);
        log.info("🧠 Orchestrator NLP route: nlpUrl={}, correlationId={}", nlpUrl, correlationId);

        // Auto-detect language from text if not provided
        Language lang = language != null && !language.isBlank()
                ? Language.fromCode(language)
                : LanguageDetector.detect(text);

        NlpClient.NlpResult result = analyzeWithResilience(text, correlationId);
        if (result == null) {
            // nlp-service degraded (retries exhausted or circuit open): degrade to the
            // existing LLM/rule-based fallback instead of failing the whole request.
            log.warn("🔍 NLP_DEGRADED: falling back without intent classification, correlationId={}", correlationId);
            String fallback = callLlm(text, correlationId, lang, userId);
            rememberExecutionMetadata(ExecutionMetadata.failure(false, false,
                    "nlp_unavailable: retries exhausted or circuit open"));
            return fallback;
        }
        log.info("🔍 NLP Result: intent={}, slots={}, correlationId={}", result.intent(), result.slots(),
                correlationId);
        return executeIntent(result.intent(), result.slots(), lang.getCode(), correlationId, text, userId);
    }

    /**
     * Calls nlp-service with a bounded retry (analyze is a pure read, safe to
     * retry) guarded by a circuit breaker. Returns {@code null} when the
     * circuit is open or all retries are exhausted, signaling the caller to
     * degrade gracefully rather than propagate the failure.
     */
    private NlpClient.NlpResult analyzeWithResilience(String text, String correlationId) {
        if (!nlpCircuitBreaker.tryAcquire()) {
            log.warn("🔍 NLP_CIRCUIT_OPEN: skipping analyze call, correlationId={}", correlationId);
            return null;
        }
        try {
            NlpClient.NlpResult result = RetryWithBackoff.call(
                    () -> nlpClient.analyze(new NlpClient.AnalyzeRequest(text)),
                    nlpRetryMaxAttempts,
                    Duration.ofMillis(nlpRetryInitialBackoffMs));
            nlpCircuitBreaker.recordSuccess();
            return result;
        } catch (RuntimeException e) {
            nlpCircuitBreaker.recordFailure(nlpCircuitBreakerFailureThreshold,
                    Duration.ofSeconds(nlpCircuitBreakerResetTimeoutSeconds));
            log.warn("🔍 NLP_CALL_FAILED after retries: {}, correlationId={}", e.getMessage(), correlationId);
            return null;
        }
    }

    public String executeIntent(String intent, Map<String, String> slots, String language, String correlationId,
            String originalText) {
        return executeIntent(intent, slots, language, correlationId, originalText, null);
    }

    @Override
    public IntentExecutionResult executeIntentDetailed(
            String intent,
            Map<String, String> slots,
            String language,
            String correlationId,
            String originalText,
            String userId) {
        executionMetadata.set(ExecutionMetadata.none());
        try {
            String responseText = executeIntent(intent, slots, language, correlationId, originalText, userId);
            ExecutionMetadata metadata = executionMetadata.get();
            return new IntentExecutionResult(
                    responseText,
                    metadata.executorFound(),
                    metadata.executionAttempted(),
                    metadata.executionSucceeded(),
                    isExecutionFailure(metadata),
                    metadata.failureReason());
        } finally {
            executionMetadata.remove();
        }
    }

    /**
     * Voice/PC bridge: dispatch a RAW pc-control action (VOLUME_UP, VOLUME_DOWN, OPEN_APP,
     * OPEN_URL, NEXT, ...) through the SAME working api-gateway → desktop WebSocket executor
     * path the intent handlers use, reusing {@link #dispatchPcAction} (including its
     * host-pc-control fallback). Returns the api-gateway-shaped response so a caller
     * (voice-gateway, which is NetworkPolicy-blocked from calling api-gateway directly)
     * can parse it exactly like a direct api-gateway dispatch instead of duplicating a
     * separate, blocked path.
     */
    public Map<String, Object> dispatchPcActionForClient(
            String action, Map<String, Object> params, String userId, String correlationId) {
        executionMetadata.set(ExecutionMetadata.none());
        try {
            PcDispatchResult result = dispatchPcAction(
                    action, params == null ? Map.of() : params, correlationId, userId);
            boolean failed = !result.executionSucceeded()
                    && (result.executionAttempted()
                            || (result.failureReason() != null && !result.failureReason().isBlank()));
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("action", action);
            body.put("executorFound", result.executorFound());
            body.put("executionAttempted", result.executionAttempted());
            body.put("executionSucceeded", result.executionSucceeded());
            body.put("executionFailed", failed);
            body.put("status", result.executionSucceeded()
                    ? "executed"
                    : (failed ? "execution_failed" : "no_clients"));
            if (result.failureReason() != null && !result.failureReason().isBlank()) {
                body.put("failureReason", result.failureReason());
                body.put("message", result.failureReason());
            }
            return body;
        } finally {
            executionMetadata.remove();
        }
    }

    @Override
    public String executeIntent(String intent, Map<String, String> slots, String language, String correlationId,
            String originalText, String userId) {
        executionMetadata.set(ExecutionMetadata.none());
        // Normalize intent to lowercase snake_case for consistent matching
        String normalizedIntent = normalizeIntent(intent);

        // Determine language
        Language lang = Language.fromCode(language);

        log.info("🎯 Executing intent: {} (normalized: {}), slots={}, lang={}, correlationId={}",
                intent, normalizedIntent, slots, lang, correlationId);

        try {
            return switch (normalizedIntent) {
                // ==================== Greetings & Small Talk ====================
                case "hello", "greeting" -> {
                    log.info("👋 Greeting, correlationId={}", correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.GREETING, lang);
                }
                case "morning_greeting" -> {
                    log.info("🌅 Morning greeting, correlationId={}", correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.MORNING_GREETING, lang);
                }
                case "goodbye" -> {
                    log.info("👋 Goodbye, correlationId={}", correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.GOODBYE, lang);
                }
                case "thanks" -> {
                    log.info("🙏 Thanks, correlationId={}", correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.THANKS, lang);
                }
                case "small_talk_jarvis" -> {
                    log.info("💬 Small talk (just wake word), correlationId={}", correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.SMALL_TALK_JARVIS, lang);
                }
                case "are_you_there" -> {
                    log.info("👁️ Are you there?, correlationId={}", correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.ARE_YOU_THERE, lang);
                }
                case "wake_response" -> phraseProvider.getPhrase(PhraseContext.WAKE_RESPONSE, lang);

                // ==================== STT Feedback ====================
                case "stt_timeout" -> {
                    log.info("⏰ STT timeout, correlationId={}", correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.STT_TIMEOUT, lang);
                }
                case "stt_noise" -> {
                    log.info("🔇 STT noise, correlationId={}", correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.STT_NOISE, lang);
                }

                // ==================== Volume Control ====================
                case "volume_up", "change_volume" -> {
                    int delta = parseIntOrDefault(slots != null ? slots.get("delta") : null, 10);
                    if (delta == 10)
                        delta = parseIntOrDefault(slots != null ? slots.get("amount") : null, 10);
                    log.info("🔊 Executing VOLUME_UP, delta={}, correlationId={}", delta, correlationId);
                    sendPcAction("VOLUME_UP", Map.of("delta", delta), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.VOLUME_UP, lang);
                }
                case "volume_down" -> {
                    int delta = parseIntOrDefault(slots != null ? slots.get("delta") : null, 10);
                    if (delta == 10)
                        delta = parseIntOrDefault(slots != null ? slots.get("amount") : null, 10);
                    log.info("🔉 Executing VOLUME_DOWN, delta={}, correlationId={}", delta, correlationId);
                    sendPcAction("VOLUME_DOWN", Map.of("delta", delta), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.VOLUME_DOWN, lang);
                }
                case "mute" -> {
                    log.info("🔇 Executing MUTE, correlationId={}", correlationId);
                    sendPcAction("MUTE", Map.of(), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.MUTE, lang);
                }
                case "unmute" -> {
                    log.info("🔊 Executing UNMUTE, correlationId={}", correlationId);
                    sendPcAction("UNMUTE", Map.of(), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.UNMUTE, lang);
                }
                case "set_volume" -> {
                    int level = parseIntOrDefault(slots != null ? slots.get("level") : null, 100);
                    log.info("🔊 Executing SET_VOLUME, level={}%, correlationId={}", level, correlationId);
                    sendPcAction("SET_VOLUME", Map.of("level", level), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.VOLUME_MAX, lang);
                }

                // ==================== Media Control ====================
                case "play", "resume", "media_toggle" -> {
                    log.info("▶️ Executing PLAY/TOGGLE, correlationId={}", correlationId);
                    sendPcAction("PLAY_PAUSE", Map.of(), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PLAY, lang);
                }
                case "pause", "stop" -> {
                    log.info("⏸️ Executing PAUSE, correlationId={}", correlationId);
                    sendPcAction("PAUSE", Map.of(), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PAUSE, lang);
                }
                case "next_track", "next", "media_next" -> {
                    log.info("⏭️ Executing NEXT, correlationId={}", correlationId);
                    sendPcAction("NEXT", Map.of(), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.NEXT_TRACK, lang);
                }
                case "previous_track", "prev", "previous", "media_prev" -> {
                    log.info("⏮️ Executing PREV, correlationId={}", correlationId);
                    sendPcAction("PREV", Map.of(), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PREVIOUS_TRACK, lang);
                }

                // ==================== App Control ====================
                case "open_app", "launch_app" -> {
                    String app = slots != null ? slots.getOrDefault("app", slots.getOrDefault("application", "browser"))
                            : "browser";
                    log.info("🚀 Executing OPEN_APP: {}, correlationId={}", app, correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", app), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_APP, lang, Map.of("app", app));
                }
                case "open_browser" -> {
                    log.info("🌐 Executing OPEN_BROWSER, correlationId={}", correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", "browser"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_BROWSER, lang);
                }
                case "open_youtube" -> {
                    log.info("🎬 Executing OPEN_YOUTUBE, correlationId={}", correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", "youtube"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_YOUTUBE, lang);
                }
                case "open_ide", "open_code", "open_notepad" -> {
                    String ide = slots != null ? slots.getOrDefault("ide", "code") : "code";
                    log.info("💻 Executing OPEN_IDE: {}, correlationId={}", ide, correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", ide), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_IDE, lang);
                }
                case "open_terminal" -> {
                    log.info("🖥️ Executing OPEN_TERMINAL, correlationId={}", correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", "terminal"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_TERMINAL, lang);
                }
                case "open_url" -> {
                    String url = slots != null ? slots.get("url") : null;
                    if (url == null || url.isBlank()) {
                        log.warn("OPEN_URL requested without url, correlationId={}", correlationId);
                        yield phraseProvider.getPhrase(PhraseContext.ACK_ERROR, lang);
                    }
                    log.info("🌐 Executing OPEN_URL: {}, correlationId={}", url, correlationId);
                    sendPcAction("OPEN_URL", Map.of("url", url), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_URL, lang);
                }
                case "open_news" -> {
                    String url = slots != null ? slots.getOrDefault("url", "https://news.google.com/") : "https://news.google.com/";
                    sendPcAction("OPEN_URL", Map.of("url", url), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_URL, lang);
                }

                // ==================== Scenarios / Protocols ====================
                case "work_mode" -> {
                    log.info("💼 Activating WORK_MODE, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "work"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.WORK_MODE, lang);
                }
                case "rest_mode", "relax_mode" -> {
                    log.info("🛋️ Activating REST_MODE, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "rest"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.REST_MODE, lang);
                }
                case "focus_mode" -> {
                    log.info("🎯 Activating FOCUS_MODE, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "focus"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.FOCUS_MODE, lang);
                }
                case "house_party", "party_mode", "protocol_house_party" -> {
                    log.info("🎉 Activating HOUSE_PARTY protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "party"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_HOUSE_PARTY, lang);
                }
                case "clean_slate", "shutdown_mode", "protocol_clean_slate" -> {
                    log.info("🧹 Activating CLEAN_SLATE protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "clean_slate"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_CLEAN_SLATE, lang);
                }
                case "protocol_cozy_evening" -> {
                    log.info("🕯️ Activating COZY_EVENING protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "cozy_evening"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_COZY_EVENING, lang);
                }
                case "protocol_guests" -> {
                    log.info("🥂 Activating GUESTS protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "guests"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_GUESTS, lang);
                }
                case "protocol_holiday" -> {
                    log.info("🎄 Activating HOLIDAY protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "holiday"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_HOLIDAY, lang);
                }
                case "game_mode" -> {
                    log.info("🎮 Activating GAME_MODE, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "game"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.GAME_MODE, lang);
                }
                case "protocol_morning" -> {
                    log.info("🌅 Activating MORNING protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "morning"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_MORNING, lang);
                }
                case "protocol_leaving" -> {
                    log.info("🚪 Activating LEAVING protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "leaving"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_LEAVING, lang);
                }
                case "protocol_panic" -> {
                    log.info("🚨 Activating PANIC protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "panic"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_PANIC, lang);
                }

                // ==================== Window Control ====================
                case "minimize_window", "window_minimize" -> {
                    log.info("⬇️ Executing MINIMIZE, correlationId={}", correlationId);
                    sendPcAction("MINIMIZE", Map.of(), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.WINDOW_MINIMIZE, lang);
                }
                case "maximize_window", "window_maximize" -> {
                    log.info("⬆️ Executing MAXIMIZE, correlationId={}", correlationId);
                    sendPcAction("MAXIMIZE", Map.of(), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.WINDOW_MAXIMIZE, lang);
                }
                case "lock_screen" -> {
                    log.info("🔒 Executing LOCK_SCREEN, correlationId={}", correlationId);
                    sendPcAction("LOCK_SCREEN", Map.of(), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.LOCK_SCREEN, lang);
                }

                // ==================== Legacy System Control ====================
                case "clipboard_copy" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "ctrl+c"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.CLIPBOARD_COPY, lang);
                }
                case "clipboard_paste" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "ctrl+v"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.CLIPBOARD_PASTE, lang);
                }
                case "undo_action" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "ctrl+z"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.UNDO_ACTION, lang);
                }
                case "switch_window" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Alt+Tab"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.SWITCH_WINDOW, lang);
                }
                case "close_window" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Alt+F4"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.CLOSE_WINDOW, lang);
                }
                case "fullscreen" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "F11"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.FULLSCREEN, lang);
                }
                case "refresh_page" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "F5"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.REFRESH_PAGE, lang);
                }
                case "navigate_back" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Alt+Left"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.NAVIGATE_BACK, lang);
                }
                case "navigate_forward" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Alt+Right"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.NAVIGATE_FORWARD, lang);
                }
                case "show_desktop" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Super+d"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.SHOW_DESKTOP, lang);
                }
                case "open_settings" -> {
                    sendPcAction("OPEN_APP", Map.of("app", "settings"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_SETTINGS, lang);
                }
                case "system_search" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Super_L"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.SYSTEM_SEARCH, lang);
                }
                case "switch_language" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Alt+Shift"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.SWITCH_LANGUAGE, lang);
                }
                case "screenshot" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Print"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.SCREENSHOT, lang);
                }
                case "sleep_mode" -> {
                    sendPcAction("SYSTEM_COMMAND", Map.of("command", "sleep"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.SLEEP_MODE, lang);
                }
                case "monitor_off" -> {
                    sendPcAction("SYSTEM_COMMAND", Map.of("command", "monitor_off"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.MONITOR_OFF, lang);
                }
                case "network_check" -> {
                    String url = slots != null ? slots.getOrDefault("url", "https://fast.com/") : "https://fast.com/";
                    sendPcAction("OPEN_URL", Map.of("url", url), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.ACK_GENERIC, lang);
                }
                case "find_in_page" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "ctrl+f"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.ACK_GENERIC, lang);
                }
                case "focus_address_bar" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "ctrl+l"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.ACK_GENERIC, lang);
                }
                case "rename_item" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "F2"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.ACK_GENERIC, lang);
                }
                case "delete_selection" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Delete"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.ACK_GENERIC, lang);
                }
                case "press_enter" -> {
                    sendPcAction("HOTKEY", Map.of("keyCombination", "Return"), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.ACK_GENERIC, lang);
                }

                // ==================== Conversation / Personality ====================
                case "welcome_home" -> phraseProvider.getPhrase(PhraseContext.WELCOME_HOME, lang);
                case "how_are_you" -> phraseProvider.getPhrase(PhraseContext.HOW_ARE_YOU, lang);
                case "what_doing" -> phraseProvider.getPhrase(PhraseContext.WHAT_DOING, lang);
                case "bored" -> phraseProvider.getPhrase(PhraseContext.BORED, lang);
                case "cheer_up" -> phraseProvider.getPhrase(PhraseContext.CHEER_UP, lang);
                case "love_response" -> phraseProvider.getPhrase(PhraseContext.LOVE_RESPONSE, lang);
                case "random_fact" -> phraseProvider.getPhrase(PhraseContext.RANDOM_FACT, lang);
                case "standby_mode" -> phraseProvider.getPhrase(PhraseContext.STANDBY_MODE, lang);

                // ==================== Read-only queries (time/date) ====================
                case "get_time", "what_time", "current_time" -> {
                    java.time.LocalTime now = java.time.LocalTime.now(resolveZoneId());
                    String hhmm = String.format("%02d:%02d", now.getHour(), now.getMinute());
                    log.info("🕐 get_time -> {}, correlationId={}", hhmm, correlationId);
                    yield lang == Language.RU ? "Сейчас " + hhmm + ", сэр."
                            : "It is " + hhmm + ", sir.";
                }
                case "get_date", "what_date", "current_date" -> {
                    java.time.LocalDate today = java.time.LocalDate.now(resolveZoneId());
                    log.info("📅 get_date -> {}, correlationId={}", today, correlationId);
                    yield lang == Language.RU ? "Сегодня " + today + ", сэр."
                            : "Today is " + today + ", sir.";
                }

                // ==================== Media / Legacy URL actions ====================
                case "play_music" -> {
                    String url = slots != null ? slots.get("url") : null;
                    if (url != null && !url.isBlank()) {
                        sendPcAction("OPEN_URL", Map.of("url", url), correlationId, userId);
                    } else {
                        sendPcAction("PLAY_PAUSE", Map.of(), correlationId, userId);
                    }
                    yield phraseProvider.getPhrase(PhraseContext.PLAY_MUSIC, lang);
                }
                case "play_radio" -> {
                    String url = slots != null ? slots.getOrDefault("url", "https://radio.garden/") : "https://radio.garden/";
                    sendPcAction("OPEN_URL", Map.of("url", url), correlationId, userId);
                    yield phraseProvider.getPhrase(PhraseContext.PLAY_RADIO, lang);
                }

                // ==================== Timer ====================
                case "set_timer" -> {
                    String amount = slots != null ? slots.getOrDefault("amount", "60") : "60";
                    String unit = slots != null ? slots.getOrDefault("unit", "sec") : "sec";
                    int seconds;
                    try {
                        int val = Integer.parseInt(amount);
                        seconds = "min".equals(unit) ? val * 60 : val;
                    } catch (NumberFormatException e) {
                        seconds = 60;
                    }
                    log.info("⏱️ Setting TIMER: {} {}, correlationId={}", amount, unit, correlationId);
                    sendPcAction("NOTIFY", Map.of(
                            "title", lang == Language.RU ? "Таймер" : "Timer",
                            "message", lang == Language.RU ? "Установлен на " + amount + " " + unit
                                    : "Set for " + amount + " " + unit),
                            correlationId, userId);

                    String unitLocalized = lang == Language.RU
                            ? ("min".equals(unit) ? "минут" : "секунд")
                            : unit;
                    yield phraseProvider.getPhrase(PhraseContext.TIMER_SET, lang,
                            Map.of("amount", amount, "unit", unitLocalized));
                }
                case "smart_home_action" -> handleSmartHomeAction(slots, lang, correlationId, userId);

                // ==================== Fallback ====================
                case "fallback", "unknown" -> callLlm(originalText, correlationId, lang, userId);
                default -> {
                    log.warn("⚠️ Unknown intent: {} (normalized: {}), correlationId={}", intent, normalizedIntent,
                            correlationId);
                    yield callLlm(originalText, correlationId, lang, userId);
                }
            };
        } catch (RuntimeException e) {
            if (!executionMetadata.get().executionAttempted()) {
                rememberExecutionMetadata(ExecutionMetadata.failure(false, false, e.getMessage()));
            }
            log.error("❌ Error executing intent: {}, correlationId={}", intent, correlationId, e);
            return phraseProvider.getPhrase(PhraseContext.ACK_ERROR, lang);
        }
    }

    private String handleSmartHomeAction(
            Map<String, String> slots,
            Language lang,
            String correlationId,
            String userId) {
        String deviceId = slots != null ? slots.get("deviceId") : null;
        String action = slots != null ? slots.get("action") : null;
        String payload = slots != null ? slots.get("payload") : null;
        if (deviceId == null || deviceId.isBlank() || action == null || action.isBlank()) {
            throw new IllegalArgumentException("smart_home_action requires deviceId and action");
        }

        String scopedUserId = userId != null && !userId.isBlank() ? userId : "local-user";
        SmartHomeClient.ActionResult result = smartHomeClient.executeAction(
                scopedUserId,
                deviceId,
                new SmartHomeClient.ActionRequest(action, payload));
        rememberExecutionMetadata(ExecutionMetadata.success());

        // Best-effort desktop notification only: the smart-home action itself already
        // succeeded above, so a failure here (both API-Gateway and direct pc-control
        // unreachable) must not be allowed to escape and be mistaken by the generic
        // RuntimeException handler in executeIntent() for the smart-home action itself
        // failing (which would overwrite the correct success phrase with ACK_ERROR).
        try {
            sendPcAction("NOTIFY", Map.of(
                    "title", lang == Language.RU ? "Умный дом" : "Smart Home",
                    "message", buildSmartHomeNotification(result)), correlationId, scopedUserId);
        } catch (PcActionDispatchException e) {
            log.warn(
                    "⚠️ Smart-home NOTIFY dispatch failed (best-effort, ignoring): deviceId={}, action={}, reason={}, correlationId={}",
                    deviceId, action, e.getMessage(), correlationId);
        }

        return phraseProvider.getPhrase(
                resolveSmartHomePhraseContext(result),
                lang,
                buildSmartHomePhraseParams(deviceId, result, lang));
    }

    /**
     * Normalize intent to lowercase snake_case for consistent matching.
     * Handles various formats: VOLUME_UP, VolumeUp, volume-up -> volume_up
     */
    private String normalizeIntent(String intent) {
        if (intent == null)
            return "unknown";

        // Convert to lowercase and replace hyphens with underscores
        String normalized = intent.toLowerCase(Locale.ROOT)
                .replace("-", "_")
                .replaceAll("([a-z])([A-Z])", "$1_$2") // camelCase to snake_case
                .toLowerCase(Locale.ROOT);

        return normalized;
    }

    /**
     * Send PC action to Desktop via API Gateway WebSocket.
     */
    private void sendPcAction(String action, Map<String, Object> params, String correlationId, String userId) {
        PcDispatchResult dispatchResult = dispatchPcAction(action, params, correlationId, userId);
        if (!dispatchResult.executionSucceeded()) {
            throw new PcActionDispatchException(
                    dispatchResult.failureReason() != null
                            ? dispatchResult.failureReason()
                            : "PC action execution failed",
                    dispatchResult);
        }
    }

    private PcDispatchResult dispatchPcAction(String action, Map<String, Object> params, String correlationId, String userId) {
        try {
            log.info("📤 Sending PC action via API Gateway: action={}, params={}, apiGatewayUrl={}, correlationId={}, userId={}",
                    action, params, apiGatewayUrl, correlationId, userId);
            var result = apiGatewayPcClient.sendPcAction(
                    new ApiGatewayPcClient.PcActionRequest(action, params, userId, correlationId));
            PcDispatchResult dispatchResult = parseApiGatewayDispatchResult(result);
            rememberPcActionMetadata(action, dispatchResult);
            log.info(
                    "📥 API Gateway PC action result: action={}, executionAttempted={}, executionSucceeded={}, failureReason={}, correlationId={}, rawResult={}",
                    action,
                    dispatchResult.executionAttempted(),
                    dispatchResult.executionSucceeded(),
                    dispatchResult.failureReason(),
                    correlationId,
                    result);
            if (!dispatchResult.executionSucceeded()) {
                if (!dispatchResult.executorFound() || !dispatchResult.executionAttempted()) {
                    log.warn(
                            "⚠️ API Gateway PC action was not executed (action={}, reason={}); trying direct host pc-control, correlationId={}",
                            action,
                            dispatchResult.failureReason(),
                            correlationId);
                    PcDispatchResult direct = tryDirectPcControl(action, params, userId, correlationId);
                    if (direct != null) {
                        return direct;
                    }
                } else {
                    // The desktop executor was found AND actually attempted the action (e.g. an
                    // ack_timeout/soft-failure on the gateway side after the action already ran).
                    // Do NOT retry via the independent direct host pc-control channel here: for
                    // non-idempotent actions (MUTE/UNMUTE, NEXT_TRACK, undo, ...) that would risk
                    // executing the same action a second time. Surface the failure to the caller
                    // instead.
                    log.warn(
                            "⚠️ API Gateway PC action was attempted by the desktop executor but reported failure "
                                    + "(action={}, reason={}); NOT retrying via direct host pc-control to avoid a "
                                    + "duplicate, non-idempotent execution, correlationId={}",
                            action,
                            dispatchResult.failureReason(),
                            correlationId);
                }
            }
            return dispatchResult;
        } catch (RuntimeException e) {
            log.warn("⚠️ Failed to send PC action via API Gateway ({}), apiGatewayUrl={}, falling back to direct host pc-control, correlationId={}",
                    e.getMessage(), apiGatewayUrl, correlationId);
            PcDispatchResult direct = tryDirectPcControl(action, params, userId, correlationId);
            if (direct != null) {
                return direct;
            }
            PcDispatchResult dispatchResult = new PcDispatchResult(false, true, false, e.getMessage(), Map.of());
            rememberPcActionMetadata(action, dispatchResult);
            return dispatchResult;
        }
    }

    /**
     * Direct HTTP path to pc-control (host bridge), used when the WebSocket desktop
     * executor is not connected. Returns the success result, or null if it also fails.
     */
    private PcDispatchResult tryDirectPcControl(String action, Map<String, Object> params, String userId,
                                                String correlationId) {
        try {
            Map<String, String> stringParams = new HashMap<>();
            params.forEach((k, v) -> stringParams.put(k, String.valueOf(v)));
            pcControlClient.executeAction(userId != null && !userId.isBlank() ? userId : "local-user",
                    new PcControlClient.ActionRequest(action, stringParams));
            PcDispatchResult dispatchResult = new PcDispatchResult(
                    true, true, true, null, Map.of("backend", "direct-pc-control"));
            rememberPcActionMetadata(action, dispatchResult);
            log.info("✅ Direct host pc-control executed: action={}, correlationId={}", action, correlationId);
            return dispatchResult;
        } catch (RuntimeException ex) {
            log.warn("⚠️ Direct host pc-control failed: action={}, error={}, correlationId={}",
                    action, ex.getMessage(), correlationId);
            return null;
        }
    }

    private PcDispatchResult parseApiGatewayDispatchResult(Map<String, Object> result) {
        boolean executorFound = readBoolean(result, "executorFound");
        boolean executionAttempted = readBoolean(result, "executionAttempted");
        boolean executionSucceeded = readBoolean(result, "executionSucceeded");
        String failureReason = readString(result, "failureReason");
        if ((failureReason == null || failureReason.isBlank()) && !executionSucceeded) {
            failureReason = readString(result, "message");
        }
        return new PcDispatchResult(
                executorFound,
                executionAttempted,
                executionSucceeded,
                failureReason,
                result != null ? Map.copyOf(result) : Map.of());
    }

    private void rememberPcActionMetadata(String action, PcDispatchResult dispatchResult) {
        if ("NOTIFY".equalsIgnoreCase(action) && executionMetadata.get().executionAttempted()) {
            return;
        }
        if (dispatchResult.executionSucceeded()) {
            rememberExecutionMetadata(ExecutionMetadata.success());
        } else {
                rememberExecutionMetadata(ExecutionMetadata.failure(
                        dispatchResult.executorFound(),
                        dispatchResult.executionAttempted(),
                        dispatchResult.failureReason()));
        }
    }

    private void rememberExecutionMetadata(ExecutionMetadata metadata) {
        executionMetadata.set(metadata);
    }

    /**
     * Resolves the configured {@code jarvis.timezone} zone for get_time/get_date,
     * falling back to UTC if it is missing or not a valid zone id rather than
     * silently using the JVM's default zone (which may not reflect the user's
     * actual local time in a container).
     */
    private java.time.ZoneId resolveZoneId() {
        try {
            return java.time.ZoneId.of(timezone);
        } catch (java.time.DateTimeException | NullPointerException e) {
            log.warn("⚠️ Invalid jarvis.timezone value '{}', falling back to UTC", timezone);
            return java.time.ZoneOffset.UTC;
        }
    }

    private boolean isExecutionFailure(ExecutionMetadata metadata) {
        return metadata != null
                && !metadata.executionSucceeded()
                && (metadata.executionAttempted()
                        || (metadata.failureReason() != null && !metadata.failureReason().isBlank()));
    }

    private boolean readBoolean(Map<String, Object> result, String key) {
        if (result == null) {
            return false;
        }
        Object value = result.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String readString(Map<String, Object> result, String key) {
        if (result == null) {
            return null;
        }
        Object value = result.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null || value.isBlank())
            return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private PhraseContext resolveSmartHomePhraseContext(SmartHomeClient.ActionResult result) {
        String action = result.action();
        if ("TURN_ON".equals(action) || ("TOGGLE".equals(action) && isPowered(result.device()))) {
            return PhraseContext.SMART_HOME_TURN_ON;
        }
        if ("TURN_OFF".equals(action) || ("TOGGLE".equals(action) && !isPowered(result.device()))) {
            return PhraseContext.SMART_HOME_TURN_OFF;
        }
        return PhraseContext.SMART_HOME_SET_VALUE;
    }

    private Map<String, Object> buildSmartHomePhraseParams(
            String deviceId,
            SmartHomeClient.ActionResult result,
            Language lang) {
        Map<String, Object> params = new HashMap<>();
        params.put("device", localizedSmartHomeDeviceName(deviceId, lang));
        if (result.device() != null && result.device().state() != null) {
            Object targetTemperature = result.device().state().get("targetTemperature");
            Object brightness = result.device().state().get("brightness");
            if (targetTemperature != null) {
                params.put("value", targetTemperature + "°C");
            } else if (brightness != null) {
                params.put("value", brightness + "%");
            }
        }
        return params;
    }

    private String buildSmartHomeNotification(SmartHomeClient.ActionResult result) {
        if (result.device() == null) {
            return "Smart-home action completed.";
        }

        String displayName = result.device().displayName();
        if ("SET_TEMPERATURE".equals(result.action())) {
            Object targetTemperature = result.device().state() != null
                    ? result.device().state().get("targetTemperature")
                    : null;
            return targetTemperature != null
                    ? displayName + " set to " + targetTemperature + "°C."
                    : displayName + " updated.";
        }
        if ("TURN_OFF".equals(result.action()) || ("TOGGLE".equals(result.action()) && !isPowered(result.device()))) {
            return displayName + " is now off.";
        }
        if ("TURN_ON".equals(result.action()) || "TOGGLE".equals(result.action())) {
            return displayName + " is now on.";
        }
        return displayName + " updated.";
    }

    private boolean isPowered(SmartHomeClient.DeviceView device) {
        if (device == null || device.state() == null) {
            return false;
        }

        Object power = device.state().get("power");
        if (power instanceof Boolean powered) {
            return powered;
        }

        Object locked = device.state().get("locked");
        if (locked instanceof Boolean lockedState) {
            return lockedState;
        }
        return false;
    }

    private String localizedSmartHomeDeviceName(String deviceId, Language lang) {
        return switch (deviceId) {
            case "kitchen_light" -> lang == Language.RU ? "кухонный свет" : "kitchen light";
            case "desk_lamp" -> lang == Language.RU ? "настольная лампа" : "desk lamp";
            case "hall_thermostat" -> lang == Language.RU ? "термостат в коридоре" : "hall thermostat";
            case "front_door_lock" -> lang == Language.RU ? "замок входной двери" : "front door lock";
            default -> deviceId.replace('_', ' ');
        };
    }

    /**
     * Explicit model profile for orchestrator-originated LLM calls.
     * Ensures deterministic profile selection when no inbound HTTP request
     * context is available (executor thread pool strips servlet attributes).
     */
    static final String ORCHESTRATOR_DEFAULT_PROFILE = "orchestration";

    /**
     * Call LLM service with circuit breaker, timeout, and fallback.
     * 
     * If LLM is disabled, times out, or circuit is open → returns rule-based phrase immediately.
     * Never blocks the response pipeline indefinitely.
     */
    private String callLlm(String text, String correlationId, Language lang, String userId) {
        // Check 1: Feature flag
        if (!llmEnabled) {
            log.debug("🧠 LLM_SKIPPED: feature disabled, correlationId={}", correlationId);
            return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
        }

        // Check 2: Circuit breaker
        if (!llmCircuitBreaker.tryAcquire()) {
            log.warn("🧠 LLM_CIRCUIT_OPEN: disabled (state={}), correlationId={}",
                    llmCircuitBreaker.getState(), correlationId);
            return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
        }

        // Call LLM with timeout
        try {
            log.info("🧠 Calling LLM for text: '{}', timeout={}s, profile={}, correlationId={}",
                    text, llmTimeoutSeconds, ORCHESTRATOR_DEFAULT_PROFILE, correlationId);

            String sessionId = (userId != null && !userId.isBlank())
                    ? userId + "-" + correlationId
                    : correlationId;
            var message = new org.jarvis.orchestrator.dto.LlmChatRequest.Message("user", text);
            var request = new org.jarvis.orchestrator.dto.LlmChatRequest(sessionId, java.util.List.of(message));

            // Execute with timeout — explicit profile avoids null-header fallback on executor threads
            Future<org.jarvis.orchestrator.dto.LlmChatResponse> future = llmExecutor.submit(
                    () -> llmClient.chat(request, correlationId, userId, ORCHESTRATOR_DEFAULT_PROFILE));

            org.jarvis.orchestrator.dto.LlmChatResponse response;
            try {
                response = future.get(llmTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("🧠 LLM_TIMEOUT: exceeded {}s, correlationId={}", llmTimeoutSeconds, correlationId);
                recordLlmFailure();
                return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
            }

            // Success - reset circuit breaker
            llmCircuitBreaker.recordSuccess();

            log.info("🧠 LLM_SUCCESS: reply='{}', correlationId={}", 
                    truncate(response.reply(), 100), correlationId);
            return response.reply();

        } catch (RejectedExecutionException e) {
            recordLlmFailure();
            log.warn("🧠 LLM_REJECTED: executor overloaded, queueSize={}, activeThreads={}, rejectedCount={}, correlationId={}",
                    llmExecutor.getQueue().size(),
                    llmExecutor.getActiveCount(),
                    rejectedLlmTasks.get(),
                    correlationId);
            return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("🧠 LLM_INTERRUPTED: {}, correlationId={}", e.getMessage(), correlationId);
            recordLlmFailure();
            return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
        } catch (ExecutionException | RuntimeException e) {
            log.error("🧠 LLM_ERROR: {}, correlationId={}", e.getMessage(), correlationId);
            recordLlmFailure();
            return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
        }
    }

    private ThreadPoolExecutor createLlmExecutor(OrchestratorExecutorProperties properties) {
        int corePoolSize = Math.max(1, properties.getCorePoolSize());
        int maxPoolSize = Math.max(corePoolSize, properties.getMaxPoolSize());
        int queueCapacity = Math.max(1, properties.getQueueCapacity());
        int keepAliveSeconds = Math.max(1, properties.getKeepAliveSeconds());

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadCounter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable);
                thread.setName("orchestrator-llm-" + threadCounter.getAndIncrement());
                thread.setDaemon(false);
                return thread;
            }
        };

        RejectedExecutionHandler rejectionHandler = (runnable, executor) -> {
            long rejected = rejectedLlmTasks.incrementAndGet();
            log.warn("🧠 LLM_EXECUTOR_REJECT: active={}, pool={}, queue={}/{}, rejectedCount={}",
                    executor.getActiveCount(),
                    executor.getPoolSize(),
                    executor.getQueue().size(),
                    queueCapacity,
                    rejected);
            throw new RejectedExecutionException("LLM executor queue is full");
        };

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                rejectionHandler);
        executor.allowCoreThreadTimeOut(false);

        log.info("🧠 LLM executor initialized: corePoolSize={}, maxPoolSize={}, queueCapacity={}, keepAliveSeconds={}",
                corePoolSize, maxPoolSize, queueCapacity, keepAliveSeconds);
        return executor;
    }

    @PreDestroy
    void shutdownLlmExecutor() {
        log.info("🧠 Shutting down LLM executor...");
        llmExecutor.shutdown();
        try {
            boolean terminated = llmExecutor.awaitTermination(
                    Math.max(1, executorProperties.getShutdownAwaitSeconds()),
                    TimeUnit.SECONDS);
            if (!terminated) {
                log.warn("🧠 LLM executor did not stop in time, forcing shutdown");
                llmExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("🧠 Interrupted while waiting for LLM executor shutdown, forcing shutdown");
            llmExecutor.shutdownNow();
        }
    }

    long getRejectedLlmTasksCount() {
        return rejectedLlmTasks.get();
    }

    ThreadPoolExecutor getLlmExecutor() {
        return llmExecutor;
    }

    /**
     * Record an LLM call failure against the shared circuit breaker, potentially
     * opening (or re-opening, after a failed half-open trial) the circuit.
     */
    private void recordLlmFailure() {
        llmCircuitBreaker.recordFailure(
                circuitBreakerFailureThreshold, Duration.ofSeconds(circuitBreakerResetTimeoutSeconds));
    }

    /**
     * Truncate string for logging.
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "<null>";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
