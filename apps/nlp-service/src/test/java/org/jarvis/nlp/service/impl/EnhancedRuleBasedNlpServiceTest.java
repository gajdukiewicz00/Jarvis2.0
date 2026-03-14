package org.jarvis.nlp.service.impl;

import org.jarvis.nlp.model.EnhancedNlpResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnhancedRuleBasedNlpServiceTest {

    private EnhancedRuleBasedNlpService service;

    @BeforeEach
    void setUp() {
        service = new EnhancedRuleBasedNlpService();
    }

    @Test
    void analyzeWithConfidenceReturnsHighConfidenceForGreeting() {
        EnhancedNlpResult result = service.analyzeWithConfidence("Привет, Джарвис", "ru");

        assertEquals("hello", result.intent());
        assertTrue(result.isHighConfidence());
        assertFalse(result.needsClarification());
    }

    @Test
    void analyzeWithConfidenceParsesExplicitTimerRequest() {
        EnhancedNlpResult result = service.analyzeWithConfidence("Поставь таймер на пятнадцать минут", "ru");

        assertEquals("set_timer", result.intent());
        assertEquals("15", result.entities().get("amount"));
        assertEquals("min", result.entities().get("unit"));
        assertEquals(0.9, result.confidence());
    }

    @Test
    void analyzeWithConfidenceRequestsClarificationForUnknownCommand() {
        EnhancedNlpResult result = service.analyzeWithConfidence("расскажи анекдот про базу данных", "ru");

        assertEquals("fallback", result.intent());
        assertTrue(result.needsClarification());
        assertTrue(result.isLowConfidence());
        assertEquals("Я не уверен, что вы имеете в виду. Можете переформулировать?", result.clarificationQuestion());
    }
}
