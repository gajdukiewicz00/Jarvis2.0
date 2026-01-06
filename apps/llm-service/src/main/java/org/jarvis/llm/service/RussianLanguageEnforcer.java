package org.jarvis.llm.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.config.LocaleConfig;
import org.jarvis.llm.dto.ChatMessageDto;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ensures all LLM responses are in Russian
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RussianLanguageEnforcer {
    
    private final LocaleConfig localeConfig;
    
    /**
     * Add language enforcement message if needed
     */
    public void enforceRussianInMessages(List<ChatMessageDto> messages) {
        if (!localeConfig.isForceRussian()) {
            return;
        }
        
        // Check if system message already mentions language
        boolean hasLanguageInstruction = messages.stream()
            .filter(m -> m.getRole() == ChatMessageDto.Role.SYSTEM)
            .anyMatch(m -> m.getContent().toLowerCase().contains("русск"));
        
        if (!hasLanguageInstruction) {
            log.debug("Adding Russian language enforcement to system prompt");
            messages.add(0, new ChatMessageDto(
                ChatMessageDto.Role.SYSTEM,
                "ВАЖНО: Отвечай ТОЛЬКО на русском языке. Это обязательное требование."
            ));
        }
    }
    
    /**
     * Validate response is in Russian (basic check)
     */
    public boolean isRussian(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        
        // Count Cyrillic characters
        long cyrillicCount = text.chars()
            .filter(c -> (c >= 0x0400 && c <= 0x04FF)) // Cyrillic Unicode block
            .count();
        
        long totalLetters = text.chars()
            .filter(Character::isLetter)
            .count();
        
        if (totalLetters == 0) {
            return true; // No letters, probably numbers/symbols
        }
        
        // If more than 50% of letters are Cyrillic, consider it Russian
        double ratio = (double) cyrillicCount / totalLetters;
        return ratio > 0.5;
    }
    
    /**
     * Log warning if response is not in Russian
     */
    public void validateResponse(String response) {
        if (localeConfig.isForceRussian() && !isRussian(response)) {
            log.warn("LLM response may not be in Russian: {}", 
                response.substring(0, Math.min(100, response.length())));
        }
    }
}
