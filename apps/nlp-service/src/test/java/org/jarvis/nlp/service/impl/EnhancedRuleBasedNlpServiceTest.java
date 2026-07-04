package org.jarvis.nlp.service.impl;

import org.jarvis.nlp.model.EnhancedNlpResult;
import org.jarvis.nlp.model.NlpResult;
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

    @Test
    void analyzeWithConfidenceParsesShortTimerAsMediumConfidence() {
        EnhancedNlpResult result = service.analyzeWithConfidence("таймер 10", "ru");

        assertEquals("set_timer", result.intent());
        assertEquals("10", result.entities().get("amount"));
        assertEquals("min", result.entities().get("unit"));
        assertEquals(0.7, result.confidence());
        assertTrue(result.isMediumConfidence());
    }

    @Test
    void analyzeWithConfidenceParsesVolumeUpWithExplicitAmount() {
        EnhancedNlpResult result = service.analyzeWithConfidence("прибавь громкость на 20", "ru");

        assertEquals("change_volume", result.intent());
        assertEquals("20", result.entities().get("deltaPercent"));
        assertEquals("+", result.entities().get("direction"));
        assertEquals(0.85, result.confidence());
    }

    @Test
    void analyzeWithConfidenceDefaultsVolumeDownDeltaWhenAmountMissing() {
        EnhancedNlpResult result = service.analyzeWithConfidence("уменьши громкость", "ru");

        assertEquals("change_volume", result.intent());
        assertEquals("10", result.entities().get("deltaPercent"));
        assertEquals("-", result.entities().get("direction"));
    }

    @Test
    void analyzeWithConfidenceParsesBareVolumeOnPhrase() {
        // "громкость на 50" doesn't contain any VOL_UP/VOL_DOWN trigger verb,
        // so it falls through to the VOL_ON pattern specifically.
        EnhancedNlpResult result = service.analyzeWithConfidence("громкость на 50", "ru");

        assertEquals("change_volume", result.intent());
        assertEquals("50", result.entities().get("deltaPercent"));
        assertEquals("+", result.entities().get("direction"));
        assertEquals(0.8, result.confidence());
    }

    @Test
    void analyzeWithConfidenceRecognizesTimeQuery() {
        EnhancedNlpResult result = service.analyzeWithConfidence("сколько сейчас времени", "ru");

        assertEquals("get_time", result.intent());
        assertEquals(0.9, result.confidence());
    }

    @Test
    void analyzeWithConfidenceRecognizesExpenseWithCategory() {
        EnhancedNlpResult result = service.analyzeWithConfidence("потратил 200 руб на такси", "ru");

        assertEquals("add_expense", result.intent());
        assertEquals("200", result.entities().get("amount"));
        assertEquals("такси", result.entities().get("category"));
        assertEquals(0.8, result.confidence());
    }

    @Test
    void analyzeWithConfidenceRecognizesReminderWithText() {
        EnhancedNlpResult result = service.analyzeWithConfidence("напомни позвонить маме", "ru");

        assertEquals("add_reminder", result.intent());
        assertEquals("позвонить маме", result.entities().get("text"));
        assertEquals(0.75, result.confidence());
    }

    @Test
    void analyzeWithConfidenceHandlesNullTextAsFallback() {
        EnhancedNlpResult result = service.analyzeWithConfidence(null, "ru");

        assertEquals("fallback", result.intent());
        assertTrue(result.needsClarification());
    }

    @Test
    void legacyInferDelegatesToAnalyzeWithConfidence() {
        NlpResult result = service.infer("Поставь таймер на пятнадцать минут", "ru");

        assertEquals("set_timer", result.intent());
        assertEquals("15", result.slots().get("amount"));
        assertEquals("min", result.slots().get("unit"));
    }
}
