package org.jarvis.orchestrator.service.impl;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.client.ApiGatewayPcClient;
import org.jarvis.orchestrator.client.NlpClient;
import org.jarvis.orchestrator.client.PcControlClient;
import org.jarvis.orchestrator.config.OrchestratorExecutorProperties;
import org.jarvis.orchestrator.phrases.JarvisPhraseProvider;
import org.jarvis.orchestrator.phrases.Language;
import org.jarvis.orchestrator.phrases.LanguageDetector;
import org.jarvis.orchestrator.phrases.PhraseContext;
import org.jarvis.orchestrator.service.OrchestratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
 */
@Slf4j
@Service
public class OrchestratorServiceImpl implements OrchestratorService {

    private final NlpClient nlpClient;
    private final PcControlClient pcControlClient;
    private final ApiGatewayPcClient apiGatewayPcClient;
    private final JarvisPhraseProvider phraseProvider;
    private final org.jarvis.orchestrator.client.LlmServiceClient llmClient;

    // LLM Feature flag and configuration
    @Value("${jarvis.llm.enabled:false}")
    private boolean llmEnabled;

    @Value("${jarvis.llm.timeout-seconds:10}")
    private int llmTimeoutSeconds;

    @Value("${jarvis.llm.circuit-breaker.failure-threshold:3}")
    private int circuitBreakerFailureThreshold;

    @Value("${jarvis.llm.circuit-breaker.reset-timeout-seconds:60}")
    private int circuitBreakerResetTimeoutSeconds;

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> circuitOpenUntil = new AtomicReference<>(Instant.EPOCH);

    // Executor for async LLM calls with timeout (bounded queue + explicit rejection policy)
    private final ThreadPoolExecutor llmExecutor;
    private final OrchestratorExecutorProperties executorProperties;
    private final AtomicLong rejectedLlmTasks = new AtomicLong(0);

    public OrchestratorServiceImpl(
            NlpClient nlpClient,
            PcControlClient pcControlClient,
            ApiGatewayPcClient apiGatewayPcClient,
            JarvisPhraseProvider phraseProvider,
            org.jarvis.orchestrator.client.LlmServiceClient llmClient,
            OrchestratorExecutorProperties executorProperties) {
        this.nlpClient = nlpClient;
        this.pcControlClient = pcControlClient;
        this.apiGatewayPcClient = apiGatewayPcClient;
        this.phraseProvider = phraseProvider;
        this.llmClient = llmClient;
        this.executorProperties = executorProperties;
        this.llmExecutor = createLlmExecutor(executorProperties);
    }

    @Override
    public String processText(String text, String language, String correlationId) {
        log.info("📝 Processing text: '{}', lang={}, correlationId={}", text, language, correlationId);

        // Auto-detect language from text if not provided
        Language lang = language != null && !language.isBlank()
                ? Language.fromCode(language)
                : LanguageDetector.detect(text);

        NlpClient.NlpResult result = nlpClient.analyze(new NlpClient.AnalyzeRequest(text));
        log.info("🔍 NLP Result: intent={}, slots={}, correlationId={}", result.intent(), result.slots(),
                correlationId);
        return executeIntent(result.intent(), result.slots(), lang.getCode(), correlationId, text);
    }

    @Override
    public String executeIntent(String intent, Map<String, String> slots, String language, String correlationId,
            String originalText) {
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
                    sendPcAction("VOLUME_UP", Map.of("delta", delta), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.VOLUME_UP, lang);
                }
                case "volume_down" -> {
                    int delta = parseIntOrDefault(slots != null ? slots.get("delta") : null, 10);
                    if (delta == 10)
                        delta = parseIntOrDefault(slots != null ? slots.get("amount") : null, 10);
                    log.info("🔉 Executing VOLUME_DOWN, delta={}, correlationId={}", delta, correlationId);
                    sendPcAction("VOLUME_DOWN", Map.of("delta", delta), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.VOLUME_DOWN, lang);
                }
                case "mute" -> {
                    log.info("🔇 Executing MUTE, correlationId={}", correlationId);
                    sendPcAction("MUTE", Map.of(), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.MUTE, lang);
                }
                case "unmute" -> {
                    log.info("🔊 Executing UNMUTE, correlationId={}", correlationId);
                    sendPcAction("UNMUTE", Map.of(), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.UNMUTE, lang);
                }
                case "set_volume" -> {
                    int level = parseIntOrDefault(slots != null ? slots.get("level") : null, 100);
                    log.info("🔊 Executing SET_VOLUME, level={}%, correlationId={}", level, correlationId);
                    sendPcAction("SET_VOLUME", Map.of("level", level), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.VOLUME_MAX, lang);
                }

                // ==================== Media Control ====================
                case "play", "resume", "media_toggle" -> {
                    log.info("▶️ Executing PLAY/TOGGLE, correlationId={}", correlationId);
                    sendPcAction("PLAY_PAUSE", Map.of(), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.PLAY, lang);
                }
                case "pause", "stop" -> {
                    log.info("⏸️ Executing PAUSE, correlationId={}", correlationId);
                    sendPcAction("PAUSE", Map.of(), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.PAUSE, lang);
                }
                case "next_track", "next", "media_next" -> {
                    log.info("⏭️ Executing NEXT, correlationId={}", correlationId);
                    sendPcAction("NEXT", Map.of(), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.NEXT_TRACK, lang);
                }
                case "previous_track", "prev", "previous", "media_prev" -> {
                    log.info("⏮️ Executing PREV, correlationId={}", correlationId);
                    sendPcAction("PREV", Map.of(), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.PREVIOUS_TRACK, lang);
                }

                // ==================== App Control ====================
                case "open_app", "launch_app" -> {
                    String app = slots != null ? slots.getOrDefault("app", slots.getOrDefault("application", "browser"))
                            : "browser";
                    log.info("🚀 Executing OPEN_APP: {}, correlationId={}", app, correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", app), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_APP, lang, Map.of("app", app));
                }
                case "open_browser" -> {
                    log.info("🌐 Executing OPEN_BROWSER, correlationId={}", correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", "browser"), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_BROWSER, lang);
                }
                case "open_youtube" -> {
                    log.info("🎬 Executing OPEN_YOUTUBE, correlationId={}", correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", "youtube"), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_YOUTUBE, lang);
                }
                case "open_ide", "open_code", "open_notepad" -> {
                    String ide = slots != null ? slots.getOrDefault("ide", "code") : "code";
                    log.info("💻 Executing OPEN_IDE: {}, correlationId={}", ide, correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", ide), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_IDE, lang);
                }
                case "open_terminal" -> {
                    log.info("🖥️ Executing OPEN_TERMINAL, correlationId={}", correlationId);
                    sendPcAction("OPEN_APP", Map.of("app", "terminal"), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.OPEN_TERMINAL, lang);
                }

                // ==================== Scenarios / Protocols ====================
                case "work_mode" -> {
                    log.info("💼 Activating WORK_MODE, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "work"), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.WORK_MODE, lang);
                }
                case "rest_mode", "relax_mode" -> {
                    log.info("🛋️ Activating REST_MODE, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "rest"), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.REST_MODE, lang);
                }
                case "focus_mode" -> {
                    log.info("🎯 Activating FOCUS_MODE, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "focus"), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.FOCUS_MODE, lang);
                }
                case "house_party", "party_mode", "protocol_house_party" -> {
                    log.info("🎉 Activating HOUSE_PARTY protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "party"), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_HOUSE_PARTY, lang);
                }
                case "clean_slate", "shutdown_mode", "protocol_clean_slate" -> {
                    log.info("🧹 Activating CLEAN_SLATE protocol, correlationId={}", correlationId);
                    sendPcAction("SCENARIO", Map.of("name", "clean_slate"), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.PROTOCOL_CLEAN_SLATE, lang);
                }

                // ==================== Window Control ====================
                case "minimize_window", "window_minimize" -> {
                    log.info("⬇️ Executing MINIMIZE, correlationId={}", correlationId);
                    sendPcAction("MINIMIZE", Map.of(), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.WINDOW_MINIMIZE, lang);
                }
                case "maximize_window", "window_maximize" -> {
                    log.info("⬆️ Executing MAXIMIZE, correlationId={}", correlationId);
                    sendPcAction("MAXIMIZE", Map.of(), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.WINDOW_MAXIMIZE, lang);
                }
                case "lock_screen" -> {
                    log.info("🔒 Executing LOCK_SCREEN, correlationId={}", correlationId);
                    sendPcAction("LOCK_SCREEN", Map.of(), correlationId);
                    yield phraseProvider.getPhrase(PhraseContext.LOCK_SCREEN, lang);
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
                            correlationId);

                    String unitLocalized = lang == Language.RU
                            ? ("min".equals(unit) ? "минут" : "секунд")
                            : unit;
                    yield phraseProvider.getPhrase(PhraseContext.TIMER_SET, lang,
                            Map.of("amount", amount, "unit", unitLocalized));
                }

                // ==================== Fallback ====================
                case "fallback", "unknown" -> callLlm(originalText, correlationId, lang);
                default -> {
                    log.warn("⚠️ Unknown intent: {} (normalized: {}), correlationId={}", intent, normalizedIntent,
                            correlationId);
                    yield callLlm(originalText, correlationId, lang);
                }
            };
        } catch (Exception e) {
            log.error("❌ Error executing intent: {}, correlationId={}", intent, correlationId, e);
            return phraseProvider.getPhrase(PhraseContext.ACK_ERROR, lang);
        }
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
    private void sendPcAction(String action, Map<String, Object> params, String correlationId) {
        try {
            log.info("📤 Sending PC action via API Gateway: action={}, params={}, correlationId={}",
                    action, params, correlationId);
            var result = apiGatewayPcClient.sendPcAction(
                    new ApiGatewayPcClient.PcActionRequest(action, params));
            log.info("📥 PC action result: {}, correlationId={}", result, correlationId);
        } catch (Exception e) {
            log.warn("⚠️ Failed to send PC action via WebSocket ({}), falling back to direct call, correlationId={}",
                    e.getMessage(), correlationId);
            // Fallback to direct K8S pc-control (stub)
            try {
                Map<String, String> stringParams = new HashMap<>();
                params.forEach((k, v) -> stringParams.put(k, String.valueOf(v)));
                pcControlClient.executeAction(new PcControlClient.ActionRequest(action, stringParams));
                log.info("✅ PC action sent via direct client, correlationId={}", correlationId);
            } catch (Exception ex) {
                log.error("❌ Failed to execute PC action: {}, correlationId={}", ex.getMessage(), correlationId);
            }
        }
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

    /**
     * Call LLM service with circuit breaker, timeout, and fallback.
     * 
     * If LLM is disabled, times out, or circuit is open → returns rule-based phrase immediately.
     * Never blocks the response pipeline indefinitely.
     */
    private String callLlm(String text, String correlationId, Language lang) {
        // Check 1: Feature flag
        if (!llmEnabled) {
            log.debug("🧠 LLM_SKIPPED: feature disabled, correlationId={}", correlationId);
            return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
        }

        // Check 2: Circuit breaker
        Instant openUntil = circuitOpenUntil.get();
        if (Instant.now().isBefore(openUntil)) {
            log.warn("🧠 LLM_CIRCUIT_OPEN: disabled until {}, correlationId={}", openUntil, correlationId);
            return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
        }

        // Call LLM with timeout
        try {
            log.info("🧠 Calling LLM for text: '{}', timeout={}s, correlationId={}", 
                    text, llmTimeoutSeconds, correlationId);

            String sessionId = correlationId;
            var message = new org.jarvis.orchestrator.dto.LlmChatRequest.Message("user", text);
            var request = new org.jarvis.orchestrator.dto.LlmChatRequest(sessionId, java.util.List.of(message));

            // Execute with timeout
            Future<org.jarvis.orchestrator.dto.LlmChatResponse> future = llmExecutor.submit(
                    () -> llmClient.chat(request, correlationId));

            org.jarvis.orchestrator.dto.LlmChatResponse response;
            try {
                response = future.get(llmTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("🧠 LLM_TIMEOUT: exceeded {}s, correlationId={}", llmTimeoutSeconds, correlationId);
                recordFailure();
                return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
            }

            // Success - reset circuit breaker
            consecutiveFailures.set(0);
            
            log.info("🧠 LLM_SUCCESS: reply='{}', correlationId={}", 
                    truncate(response.reply(), 100), correlationId);
            return response.reply();

        } catch (RejectedExecutionException e) {
            recordFailure();
            log.warn("🧠 LLM_REJECTED: executor overloaded, queueSize={}, activeThreads={}, rejectedCount={}, correlationId={}",
                    llmExecutor.getQueue().size(),
                    llmExecutor.getActiveCount(),
                    rejectedLlmTasks.get(),
                    correlationId);
            return phraseProvider.getPhrase(PhraseContext.UNKNOWN_COMMAND, lang);
        } catch (Exception e) {
            log.error("🧠 LLM_ERROR: {}, correlationId={}", e.getMessage(), correlationId);
            recordFailure();
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
     * Record a failure and potentially open the circuit breaker.
     */
    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= circuitBreakerFailureThreshold) {
            Instant until = Instant.now().plusSeconds(circuitBreakerResetTimeoutSeconds);
            circuitOpenUntil.set(until);
            log.warn("🧠 LLM_CIRCUIT_OPENED: {} consecutive failures, disabled until {}", 
                    failures, until);
            consecutiveFailures.set(0); // Reset counter after opening circuit
        }
    }

    /**
     * Truncate string for logging.
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "<null>";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
