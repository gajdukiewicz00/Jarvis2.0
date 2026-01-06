package org.jarvis.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.dto.ChatMessageDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        List<ChatMessageDto> history = sessionHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());
        history.add(message);
        
        // Trim history if needed
        if (history.size() > maxHistoryLength) {
            // Keep system messages and trim oldest user/assistant messages
            List<ChatMessageDto> systemMessages = history.stream()
                .filter(m -> m.getRole() == ChatMessageDto.Role.SYSTEM)
                .toList();
            
            List<ChatMessageDto> otherMessages = history.stream()
                .filter(m -> m.getRole() != ChatMessageDto.Role.SYSTEM)
                .skip(history.size() - maxHistoryLength + systemMessages.size())
                .toList();
            
            List<ChatMessageDto> trimmed = new ArrayList<>(systemMessages);
            trimmed.addAll(otherMessages);
            sessionHistory.put(sessionId, trimmed);
            
            log.debug("Trimmed history for session {} to {} messages", sessionId, trimmed.size());
        }
    }
    
    /**
     * Get conversation history for a session
     */
    public List<ChatMessageDto> getHistory(String sessionId) {
        return new ArrayList<>(sessionHistory.getOrDefault(sessionId, new ArrayList<>()));
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
