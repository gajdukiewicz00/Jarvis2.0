package org.jarvis.llm.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.jarvis.llm.client.UserProfileClient;
import org.jarvis.llm.config.LlmBackgroundExecutor;
import org.jarvis.llm.config.ModelProfileProperties;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.dto.DialogRequest;
import org.jarvis.llm.dto.DialogResponse;
import org.jarvis.llm.dto.UserPreferencesDto;
import org.jarvis.llm.model.Emotion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main LLM service orchestrating conversation with personalized prompts.
 * Now with long-term memory integration (RAG).
 * All responses are in Russian language.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final LlmClient llmClient;
    private final LlmConversationMemory conversationMemory;
    private final UserProfileClient userProfileClient;
    private final PersonalizedPromptBuilder promptBuilder;
    private final EmotionSelector emotionSelector;
    private final RussianLanguageEnforcer languageEnforcer;
    private final LlmBackgroundExecutor backgroundExecutor;
    private final LlmAdmissionController admissionController;
    private final ModelProfileProperties profileProperties;
    private final LlmMetrics llmMetrics;

    private final MemoryClient memoryClient;
    private final TokenBudgetManager tokenBudgetManager;

    @Value("${memory.search.top-k:5}")
    private int memoryTopK;

    @Value("${memory.search.max-tokens:600}")
    private int memoryMaxTokens;

    @Value("${memory.enabled:true}")
    private boolean memoryEnabled;

    private static final long RATE_LIMIT_MS = 2000;
    private static final int MAX_INPUT_LENGTH = 2000;
    private static final long DEFAULT_RATE_LIMIT_CACHE_MAX_USERS = 100_000;
    private static final Duration DEFAULT_RATE_LIMIT_CACHE_TTL = Duration.ofHours(1);

    @Value("${llm.rate-limit.cache.max-users:100000}")
    private long rateLimitCacheMaxUsers = DEFAULT_RATE_LIMIT_CACHE_MAX_USERS;

    @Value("${llm.rate-limit.cache.ttl:PT1H}")
    private Duration rateLimitCacheTtl = DEFAULT_RATE_LIMIT_CACHE_TTL;

    private volatile Cache<String, Long> lastRequestCache;

    /**
     * Process user message with personalized Jarvis character.
     * Now includes RAG (Retrieval-Augmented Generation) with long-term memory.
     */
    public ChatResponseDto processMessage(String sessionId, String userMessage, String correlationId) {
        return processMessage(sessionId, null, userMessage, correlationId, true);
    }

    /**
     * Process user message with explicit delegated user context when available.
     */
    public ChatResponseDto processMessage(String sessionId, String userId, String userMessage, String correlationId) {
        return processMessage(sessionId, userId, userMessage, correlationId, true);
    }

    /**
     * Process user message with profile and optional rate-limit bypass.
     */
    public ChatResponseDto processMessage(
            String sessionId,
            String userId,
            String userMessage,
            String correlationId,
            boolean enforceRateLimit) {
        return processMessage(sessionId, userId, userMessage, correlationId, enforceRateLimit, null);
    }

    /**
     * Process user message with explicit profile selection and admission control.
     *
     * @param profileName one of: voice-fast, desktop-general, background-summary, or null for default
     */
    public ChatResponseDto processMessage(
            String sessionId,
            String userId,
            String userMessage,
            String correlationId,
            boolean enforceRateLimit,
            String profileName) {
        long startTime = System.currentTimeMillis();
        log.info("[{}] Processing message for session: {}", correlationId, sessionId);

        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }

        // Rate limiting
        String effectiveUserId = (userId != null && !userId.isBlank())
                ? userId
                : extractUserId(sessionId);
        long now = System.currentTimeMillis();
        long last = getLastRequestTime(effectiveUserId);
        if (enforceRateLimit && now - last < RATE_LIMIT_MS) {
            throw new RuntimeException("Rate limit exceeded. Please wait.");
        }
        if (enforceRateLimit) {
            putLastRequestTime(effectiveUserId, now);
        }

        // Truncate input
        if (userMessage != null && userMessage.length() > MAX_INPUT_LENGTH) {
            log.warn("[{}] Truncating message from {} to {} chars", correlationId, userMessage.length(),
                    MAX_INPUT_LENGTH);
            userMessage = userMessage.substring(0, MAX_INPUT_LENGTH);
        }

        // Get user preferences (optional - returns defaults if unavailable)
        log.debug("[{}] Fetching preferences for user: {}", correlationId, effectiveUserId);
        UserPreferencesDto prefs = userProfileClient.getPreferences(effectiveUserId, correlationId);
        log.debug("[{}] Using communication style: {}", correlationId, prefs.getCommunicationStyle());
        List<String> goals = userProfileClient.getGoals(effectiveUserId, correlationId);

        // Build personalized system prompt
        String systemPrompt = promptBuilder.buildSystemPrompt(
                prefs.getFullName(),
                prefs.getTimezone(),
                prefs.getOccupation(),
                goals,
                prefs.getCommunicationStyle(),
                prefs.getAllowSarcasm());

        // === RAG: Search long-term memory for relevant context ===
        String memoryContext = "";
        if (memoryEnabled) {
            MemoryClient.SearchContextResult memoryResult = memoryClient.searchContext(
                    effectiveUserId, userMessage, memoryTopK, memoryMaxTokens, correlationId);
            memoryContext = memoryResult.contextText();
            if (!memoryContext.isBlank()) {
                log.info("[{}] Memory context found: {} chars (mode={})",
                        correlationId,
                        memoryContext.length(),
                        memoryResult.retrievalMode());
            }
            if (memoryResult.degradedReason() != null && !memoryResult.degradedReason().isBlank()) {
                log.warn("[{}] Memory retrieval degraded: mode={}, reason={}",
                        correlationId,
                        memoryResult.retrievalMode(),
                        memoryResult.degradedReason());
            }
        }

        // Build messages with token budget management
        List<ChatMessageDto> messages = tokenBudgetManager.buildMessages(
                systemPrompt,
                memoryContext,
                conversationMemory.getHistory(sessionId),
                userMessage
        );

        // Enforce Russian language
        languageEnforcer.enforceRussianInMessages(messages);

        // Resolve model profile for this request
        ModelProfileProperties.Profile profile = profileProperties.resolve(profileName);
        LlmAdmissionController.Priority priority = resolvePriority(profileName);

        // Admission control: acquire inference slot
        llmMetrics.recordChatRequest();
        try (LlmAdmissionController.AdmissionTicket ticket =
                     admissionController.tryAcquire(priority, profile.getTimeoutSeconds())) {
            if (ticket == null) {
                llmMetrics.recordAdmissionRejected();
                throw new RuntimeException("LLM inference unavailable: admission control rejected or timed out");
            }

            // Call LLM with profile parameters
            ChatResponseDto response = llmClient.chat(
                    messages, profile.getMaxTokens(), profile.getTemperature(), correlationId);
            llmMetrics.recordChatLatency(System.currentTimeMillis() - startTime);

            languageEnforcer.validateResponse(response.getReply());

            LocalTime localTime = LocalTime.now(ZoneId.of(prefs.getTimezone()));
            Emotion emotion = emotionSelector.selectEmotion(
                    userMessage, localTime, prefs.getCommunicationStyle());
            response.setEmotion(emotion);

            ChatMessageDto userMsg = new ChatMessageDto(ChatMessageDto.Role.USER, userMessage);
            ChatMessageDto assistantMsg = new ChatMessageDto(ChatMessageDto.Role.ASSISTANT, response.getReply());
            conversationMemory.addMessage(sessionId, userMsg);
            conversationMemory.addMessage(sessionId, assistantMsg);

            if (memoryEnabled) {
                final String finalUserMessage = userMessage;
                try {
                    backgroundExecutor.execute(() -> {
                        try {
                            memoryClient.ingestAsync(
                                    effectiveUserId, sessionId, finalUserMessage,
                                    response.getReply(), correlationId);
                        } catch (RuntimeException e) {
                            log.warn("[{}] Memory ingest task failed (non-fatal): {}", correlationId, e.getMessage());
                        }
                    });
                } catch (RuntimeException e) {
                    log.warn("[{}] Memory ingest failed (non-fatal): {}", correlationId, e.getMessage());
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[{}] Message processed in {}ms (profile={}, memory={})",
                    correlationId, elapsed, profileName != null ? profileName : "default",
                    memoryContext.isBlank() ? "none" : "used");
            return response;
        }
    }

    // ============== DIALOG MODE ==============
    
    private static final Set<String> EXIT_PHRASES = Set.of(
            "выйди из диалога", "выход из диалога", "хватит", "закончим", "стоп диалог",
            "exit dialog", "end dialog", "stop dialog", "enough"
    );
    
    private static final String DIALOG_SYSTEM_PROMPT = """
        Ты — JARVIS, интеллигентный персональный ИИ-ассистент.
        
        **ВАЖНО: Отвечай ТОЛЬКО на русском языке.**
        
        **Твой характер:**
        - Спокойный, собранный, интеллигентный.
        - Британский акцент с лёгкой иронией.
        - Всегда вежлив и полезен.
        - Краток, но информативен.
        
        **Правила диалога:**
        - Помни контекст разговора.
        - Если не знаешь ответ — честно скажи.
        - Не выполняй команды, только отвечай на вопросы.
        - Если пользователь хочет что-то сделать — предложи, но не делай сам.
        
        **Контекст:**
        %s
        """;
    
    /**
     * Process dialog mode interaction with session memory and context.
     * 
     * Unlike processMessage(), this method:
     * - Uses dedicated dialog system prompt
     * - Detects exit phrases to end dialog mode
     * - Returns structured DialogResponse with shouldContinue flag
     */
    public DialogResponse processDialog(DialogRequest request, String correlationId) {
        long startTime = System.currentTimeMillis();
        String sessionId = request.getSessionId();
        String userId = request.getUserId() != null ? request.getUserId() : extractUserId(sessionId);
        String input = request.getInput();
        String lang = request.getLang() != null ? request.getLang() : "ru";
        
        log.info("🗣️ Dialog mode: sessionId={}, userId={}, input='{}', correlationId={}", 
                sessionId, userId, truncate(input, 50), correlationId);
        
        // Check for exit phrases
        if (isExitPhrase(input)) {
            log.info("🚪 Exit phrase detected, ending dialog mode, correlationId={}", correlationId);
            return DialogResponse.builder()
                    .sessionId(sessionId)
                    .reply(lang.startsWith("ru") 
                            ? "Понял. Возвращаюсь в режим команд." 
                            : "Understood. Returning to command mode.")
                    .shouldContinue(false)
                    .mode("command")
                    .emotion(Emotion.NEUTRAL)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
        
        // Rate limiting
        long now = System.currentTimeMillis();
        long last = getLastRequestTime(userId);
        if (now - last < RATE_LIMIT_MS) {
            log.warn("Rate limit exceeded for user {}", userId);
            return DialogResponse.builder()
                    .sessionId(sessionId)
                    .reply("Слишком много запросов. Подожди немного.")
                    .shouldContinue(true)
                    .mode("dialog")
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
        putLastRequestTime(userId, now);
        
        // Truncate input
        if (input != null && input.length() > MAX_INPUT_LENGTH) {
            input = input.substring(0, MAX_INPUT_LENGTH);
        }
        
        try {
            // Step 1: Get user preferences (optional - returns defaults if unavailable)
            log.debug("[{}] Step 1: fetching preferences for user: {}", correlationId, userId);
            UserPreferencesDto prefs = userProfileClient.getPreferences(userId, correlationId);
            
            // Step 2: Build prompt
            String contextInfo = buildContextInfo(request.getContext(), prefs);
            String systemPrompt = String.format(DIALOG_SYSTEM_PROMPT, contextInfo);
            log.debug("[{}] Step 2: prompt built, systemPrompt.length={}", correlationId, systemPrompt.length());
            
            // Step 3: Add user message to conversation memory
            ChatMessageDto userMsg = new ChatMessageDto(ChatMessageDto.Role.USER, input);
            conversationMemory.addMessage(sessionId, userMsg);
            
            // Step 4: Build message list: [system, history]
            List<ChatMessageDto> messages = new ArrayList<>();
            messages.add(new ChatMessageDto(ChatMessageDto.Role.SYSTEM, systemPrompt));
            messages.addAll(conversationMemory.getHistory(sessionId));
            languageEnforcer.enforceRussianInMessages(messages);
            log.debug("[{}] Step 3-4: messages assembled, count={}", correlationId, messages.size());
            
            // Step 5: Call LLM
            log.info("[{}] Step 5: calling LLM...", correlationId);
            long llmStart = System.currentTimeMillis();
            ChatResponseDto llmResponse = llmClient.chat(messages, 512, 0.7, correlationId);
            long llmElapsed = System.currentTimeMillis() - llmStart;
            log.info("[{}] Step 5: LLM returned in {}ms, reply.length={}", 
                    correlationId, llmElapsed, 
                    llmResponse.getReply() != null ? llmResponse.getReply().length() : 0);
            
            // Step 6: Validate and save response
            languageEnforcer.validateResponse(llmResponse.getReply());
            ChatMessageDto assistantMsg = new ChatMessageDto(
                    ChatMessageDto.Role.ASSISTANT, llmResponse.getReply());
            conversationMemory.addMessage(sessionId, assistantMsg);
            
            // Step 7: Select emotion
            LocalTime localTime = LocalTime.now(ZoneId.of(prefs.getTimezone()));
            Emotion emotion = emotionSelector.selectEmotion(input, localTime, prefs.getCommunicationStyle());
            
            long processingTime = System.currentTimeMillis() - startTime;
            log.info("[{}] Dialog OK in {}ms (LLM: {}ms)", correlationId, processingTime, llmElapsed);
            
            return DialogResponse.builder()
                    .sessionId(sessionId)
                    .reply(llmResponse.getReply())
                    .shouldContinue(true)
                    .mode("dialog")
                    .emotion(emotion)
                    .processingTimeMs(processingTime)
                    .confidence(0.9) // LLM-based response
                    .build();
                    
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[{}] ❌ Dialog processing failed after {}ms: {}", correlationId, elapsed, e.getMessage(), e);
            return DialogResponse.builder()
                    .sessionId(sessionId)
                    .reply("Извини, произошла ошибка. Попробуй ещё раз.")
                    .shouldContinue(true)
                    .mode("dialog")
                    .emotion(Emotion.NEUTRAL)
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }
    
    private boolean isExitPhrase(String input) {
        if (input == null) return false;
        String normalized = input.toLowerCase().trim();
        return EXIT_PHRASES.stream().anyMatch(normalized::contains);
    }
    
    private String buildContextInfo(Map<String, Object> context, UserPreferencesDto prefs) {
        StringBuilder sb = new StringBuilder();
        
        // User info
        sb.append("Пользователь: ").append(prefs.getFullName() != null ? prefs.getFullName() : "Denis");
        sb.append(", timezone: ").append(prefs.getTimezone());
        
        // Add context from request if available
        if (context != null) {
            if (context.containsKey("profile")) {
                sb.append("\nПрофиль: ").append(context.get("profile"));
            }
            if (context.containsKey("planner")) {
                sb.append("\nПланы: ").append(context.get("planner"));
            }
            if (context.containsKey("analytics")) {
                sb.append("\nАналитика: ").append(context.get("analytics"));
            }
        }
        
        return sb.toString();
    }
    
    private static String truncate(String s, int maxLen) {
        if (s == null) return "<null>";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * Clear conversation history for a session
     */
    public void clearSession(String sessionId) {
        log.info("Clearing session: {}", sessionId);
        conversationMemory.clearSession(sessionId);
    }

    /**
     * Check if LLM server is available
     */
    public boolean isAvailable() {
        return llmClient.isHealthy() && (!memoryEnabled || memoryClient.isHealthy());
    }

    private String extractUserId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "anonymous";
        }
        // Simple extraction: everything before first '-' or entire string
        int dashIndex = sessionId.indexOf('-');
        if (dashIndex > 0) {
            return sessionId.substring(0, dashIndex);
        }
        return sessionId;
    }

    private LlmAdmissionController.Priority resolvePriority(String profileName) {
        if (profileName == null) return LlmAdmissionController.Priority.INTERACTIVE;
        return switch (profileName.toLowerCase()) {
            case "voice-fast", "voice" -> LlmAdmissionController.Priority.VOICE;
            case "background-summary", "background" -> LlmAdmissionController.Priority.BACKGROUND;
            default -> LlmAdmissionController.Priority.INTERACTIVE;
        };
    }

    private long getLastRequestTime(String userId) {
        Long value = rateLimitCache().getIfPresent(userId);
        return value != null ? value : 0L;
    }

    private void putLastRequestTime(String userId, long timestamp) {
        rateLimitCache().put(userId, timestamp);
    }

    private Cache<String, Long> rateLimitCache() {
        Cache<String, Long> cache = lastRequestCache;
        if (cache == null) {
            synchronized (this) {
                cache = lastRequestCache;
                if (cache == null) {
                    cache = Caffeine.newBuilder()
                            .maximumSize(safeRateLimitCacheMaxUsers())
                            .expireAfterWrite(safeRateLimitCacheTtl())
                            .build();
                    lastRequestCache = cache;
                }
            }
        }
        return cache;
    }

    private long safeRateLimitCacheMaxUsers() {
        return rateLimitCacheMaxUsers > 0 ? rateLimitCacheMaxUsers : DEFAULT_RATE_LIMIT_CACHE_MAX_USERS;
    }

    private Duration safeRateLimitCacheTtl() {
        if (rateLimitCacheTtl == null || rateLimitCacheTtl.isNegative() || rateLimitCacheTtl.isZero()) {
            return DEFAULT_RATE_LIMIT_CACHE_TTL;
        }
        return rateLimitCacheTtl;
    }
}
