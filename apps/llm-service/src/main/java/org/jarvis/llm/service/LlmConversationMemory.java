package org.jarvis.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.dto.ChatMessageDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation history management
 * 
 * Future: Replace with Redis/Postgres for persistence
 */
@Slf4j
@Service
public class LlmConversationMemory {
    
    @Value("${llm.conversation.max-history:20}")
    private int maxHistoryLength;
    
    private final Map<String, List<ChatMessageDto>> sessionHistory = new ConcurrentHashMap<>();
    
    /**
     * Add a message to session history
     */
    public void addMessage(String sessionId, ChatMessageDto message) {
        List<ChatMessageDto> history = sessionHistory.computeIfAbsent(
                sessionId, key -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (history) {
            history.add(message);

            // Trim history if needed
            if (history.size() > maxHistoryLength) {
                List<ChatMessageDto> systemMessages = history.stream()
                        .filter(m -> m.getRole() == ChatMessageDto.Role.SYSTEM)
                        .toList();

                int nonSystemLimit = Math.max(0, maxHistoryLength - systemMessages.size());
                List<ChatMessageDto> otherMessages = history.stream()
                        .filter(m -> m.getRole() != ChatMessageDto.Role.SYSTEM)
                        .skip(Math.max(0, history.stream()
                                .filter(m -> m.getRole() != ChatMessageDto.Role.SYSTEM)
                                .count() - nonSystemLimit))
                        .toList();

                history.clear();
                history.addAll(systemMessages);
                history.addAll(otherMessages);

                log.debug("Trimmed history for session {} to {} messages", sessionId, history.size());
            }
        }
    }
    
    /**
     * Get conversation history for a session
     */
    public List<ChatMessageDto> getHistory(String sessionId) {
        List<ChatMessageDto> history = sessionHistory.get(sessionId);
        if (history == null) {
            return new ArrayList<>();
        }
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }
    
    /**
     * Clear session history
     */
    public void clearSession(String sessionId) {
        sessionHistory.remove(sessionId);
        log.info("Cleared history for session: {}", sessionId);
    }
    
    /**
     * Get number of active sessions
     */
    public int getActiveSessionCount() {
        return sessionHistory.size();
    }
}
