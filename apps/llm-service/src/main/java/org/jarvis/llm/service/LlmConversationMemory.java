package org.jarvis.llm.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.logging.LogSanitizer;
import org.jarvis.llm.dto.ChatMessageDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory conversation history with TTL and bounded capacity.
 */
@Slf4j
@Service
public class LlmConversationMemory {

    private static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(60);
    private static final long DEFAULT_MAX_SESSIONS = 10_000;
    private static final int DEFAULT_MAX_MESSAGES_PER_SESSION = 100;

    @Value("${llm.memory.sessionTtl:PT60M}")
    private Duration sessionTtl = DEFAULT_SESSION_TTL;

    @Value("${llm.memory.maxSessions:10000}")
    private long maxSessions = DEFAULT_MAX_SESSIONS;

    @Value("${llm.memory.maxMessagesPerSession:100}")
    private int maxMessagesPerSession = DEFAULT_MAX_MESSAGES_PER_SESSION;

    @Value("${logging.pii.enabled:true}")
    private boolean piiLoggingEnabled = true;

    @Value("${logging.pii.allowQuerySnippet:false}")
    private boolean piiAllowQuerySnippet = false;

    @Value("${logging.pii.querySnippetMaxLength:32}")
    private int piiQuerySnippetMaxLength = 32;

    private volatile Cache<String, Deque<ChatMessageDto>> sessionHistory;

    /**
     * Add message to a bounded per-session ring buffer.
     */
    public void addMessage(String sessionId, ChatMessageDto message) {
        if (sessionId == null || sessionId.isBlank() || message == null) {
            return;
        }

        historyCache().asMap().compute(sessionId, (key, existing) -> {
            ArrayDeque<ChatMessageDto> deque = existing == null ? new ArrayDeque<>() : new ArrayDeque<>(existing);
            deque.addLast(message);

            int trimmed = 0;
            int maxMessages = safeMaxMessagesPerSession();
            while (deque.size() > maxMessages) {
                deque.pollFirst();
                trimmed++;
            }

            if (trimmed > 0 && log.isDebugEnabled()) {
                log.debug("Trimmed {} messages for session={} (maxMessages={})",
                        trimmed, logSanitizer().sanitizeId(sessionId), maxMessages);
            }

            return deque;
        });
    }

    /**
     * Return snapshot of current session history.
     */
    public List<ChatMessageDto> getHistory(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new ArrayList<>();
        }
        Deque<ChatMessageDto> history = historyCache().getIfPresent(sessionId);
        return history == null ? new ArrayList<>() : new ArrayList<>(history);
    }

    /**
     * Remove session history.
     */
    public void clearSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        historyCache().invalidate(sessionId);
        log.info("Cleared history for session={}", logSanitizer().sanitizeId(sessionId));
    }

    /**
     * Number of active sessions currently held in cache.
     */
    public int getActiveSessionCount() {
        Cache<String, Deque<ChatMessageDto>> cache = historyCache();
        cache.cleanUp();
        long size = cache.estimatedSize();
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    void configureForTests(Duration ttl, long maxSessionsLimit, int maxMessagesLimit, Ticker ticker) {
        this.sessionTtl = ttl;
        this.maxSessions = maxSessionsLimit;
        this.maxMessagesPerSession = maxMessagesLimit;
        this.sessionHistory = buildCache(ticker);
    }

    private Cache<String, Deque<ChatMessageDto>> historyCache() {
        Cache<String, Deque<ChatMessageDto>> cache = sessionHistory;
        if (cache == null) {
            synchronized (this) {
                cache = sessionHistory;
                if (cache == null) {
                    cache = buildCache(Ticker.systemTicker());
                    sessionHistory = cache;
                }
            }
        }
        return cache;
    }

    private Cache<String, Deque<ChatMessageDto>> buildCache(Ticker ticker) {
        Ticker effectiveTicker = ticker != null ? ticker : Ticker.systemTicker();
        return Caffeine.newBuilder()
                .expireAfterAccess(safeSessionTtl())
                .maximumSize(safeMaxSessions())
                .ticker(effectiveTicker)
                .removalListener((String key, Deque<ChatMessageDto> value, RemovalCause cause) -> {
                    if (key != null && cause.wasEvicted() && log.isDebugEnabled()) {
                        log.debug("Session history evicted: session={}, cause={}",
                                logSanitizer().sanitizeId(key), cause);
                    }
                })
                .build();
    }

    private Duration safeSessionTtl() {
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            return DEFAULT_SESSION_TTL;
        }
        return sessionTtl;
    }

    private long safeMaxSessions() {
        return maxSessions > 0 ? maxSessions : DEFAULT_MAX_SESSIONS;
    }

    private int safeMaxMessagesPerSession() {
        return maxMessagesPerSession > 0 ? maxMessagesPerSession : DEFAULT_MAX_MESSAGES_PER_SESSION;
    }

    private LogSanitizer logSanitizer() {
        return new LogSanitizer(piiLoggingEnabled, piiAllowQuerySnippet, piiQuerySnippetMaxLength);
    }
}
