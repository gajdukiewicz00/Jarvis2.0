package org.jarvis.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.llm.model.CommunicationStyle;
import org.jarvis.llm.model.Emotion;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * Selects appropriate emotion for TTS based on context
 */
@Slf4j
@Component
public class EmotionSelector {
    
    public Emotion selectEmotion(
            String userMessage,
            LocalTime time,
            CommunicationStyle style
    ) {
        // Morning: energetic
        if (time.isAfter(LocalTime.of(6, 0)) && time.isBefore(LocalTime.of(10, 0))) {
            log.debug("Morning time, selecting ENERGETIC emotion");
            return Emotion.ENERGETIC;
        }
        
        // Late evening/night: calm
        if (time.isAfter(LocalTime.of(22, 0)) || time.isBefore(LocalTime.of(6, 0))) {
            log.debug("Late evening, selecting CALM emotion");
            return Emotion.CALM;
        }
        
        // Check for stress/negative sentiment
        if (containsStressKeywords(userMessage)) {
            log.debug("Stress keywords detected, selecting EMPATHETIC emotion");
            return Emotion.EMPATHETIC;
        }
        
        // Check for motivational context
        if (containsMotivationalKeywords(userMessage)) {
            log.debug("Motivational context, selecting ENERGETIC emotion");
            return Emotion.ENERGETIC;
        }
        
        // Default based on style
        Emotion defaultEmotion = style == CommunicationStyle.CONCISE ? Emotion.CALM : Emotion.NEUTRAL;
        log.debug("Using default emotion: {}", defaultEmotion);
        return defaultEmotion;
    }
    
    private boolean containsStressKeywords(String message) {
        String lower = message.toLowerCase();
        return lower.contains("устал") 
            || lower.contains("заебал") 
            || lower.contains("задолбал")
            || lower.contains("проблема") 
            || lower.contains("не получается")
            || lower.contains("сложно")
            || lower.contains("трудно");
    }
    
    private boolean containsMotivationalKeywords(String message) {
        String lower = message.toLowerCase();
        return lower.contains("давай")
            || lower.contains("начн")
            || lower.contains("сдела")
            || lower.contains("план")
            || lower.contains("цел");
    }
}
