package org.jarvis.nlp.service.impl;

import org.jarvis.nlp.model.EnhancedNlpResult;
import org.jarvis.nlp.model.IntentCandidate;
import org.jarvis.nlp.model.NlpResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnhancedRuleBasedNlpServiceTest {

    private static final double DEFAULT_THRESHOLD = 0.5;
    private static final int DEFAULT_TOP_K = 3;

    private EnhancedRuleBasedNlpService service;

    @BeforeEach
    void setUp() {
        service = new EnhancedRuleBasedNlpService(DEFAULT_THRESHOLD, DEFAULT_TOP_K);
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

        assertEquals("UNKNOWN", result.intent());
        assertTrue(result.needsClarification());
        assertTrue(result.isLowConfidence());
        assertEquals("Я не уверен, что вы имеете в виду. Можете переформулировать?", result.clarificationQuestion());
        assertTrue(result.candidates().isEmpty());
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

        assertEquals("UNKNOWN", result.intent());
        assertTrue(result.needsClarification());
    }

    @Test
    void legacyInferDelegatesToAnalyzeWithConfidence() {
        NlpResult result = service.infer("Поставь таймер на пятнадцать минут", "ru");

        assertEquals("set_timer", result.intent());
        assertEquals("15", result.slots().get("amount"));
        assertEquals("min", result.slots().get("unit"));
    }

    // ---------------------------------------------------------------
    // Confidence threshold behavior
    // ---------------------------------------------------------------

    @Test
    void analyzeWithConfidenceAcceptsHighConfidenceIntentRegardlessOfThreshold() {
        EnhancedRuleBasedNlpService strict = new EnhancedRuleBasedNlpService(0.6, DEFAULT_TOP_K);

        EnhancedNlpResult result = strict.analyzeWithConfidence("прибавь громкость на 20", "ru");

        assertEquals("change_volume", result.intent());
        assertFalse(result.needsClarification());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void analyzeWithConfidenceReturnsUnknownWithCandidatesBelowConfigurableThreshold() {
        // "таймер 10" resolves to set_timer at 0.7 confidence; raising the
        // threshold above that forces the clarification path even though a
        // pattern legitimately matched.
        EnhancedRuleBasedNlpService strict = new EnhancedRuleBasedNlpService(0.75, DEFAULT_TOP_K);

        EnhancedNlpResult result = strict.analyzeWithConfidence("таймер 10", "ru");

        assertEquals("UNKNOWN", result.intent());
        assertTrue(result.needsClarification());
        assertEquals(1, result.candidates().size());
        assertEquals("set_timer", result.candidates().get(0).intent());
        assertEquals(0.7, result.candidates().get(0).confidence());
    }

    @Test
    void analyzeWithConfidenceRanksCandidatesByConfidenceDescending() {
        EnhancedRuleBasedNlpService strict = new EnhancedRuleBasedNlpService(0.99, DEFAULT_TOP_K);

        // Matches three independent signals: change_volume (0.8, VOL_ON),
        // add_reminder (0.75), and set_timer (0.7, TIMER_SHORT).
        EnhancedNlpResult result = strict.analyzeWithConfidence(
                "таймер 10 напомни купить хлеб громкость на 50", "ru");

        assertEquals("UNKNOWN", result.intent());
        assertTrue(result.candidates().size() >= 2);
        for (int i = 1; i < result.candidates().size(); i++) {
            assertTrue(result.candidates().get(i - 1).confidence() >= result.candidates().get(i).confidence());
        }
    }

    @Test
    void analyzeWithConfidenceCapsCandidateListAtConfiguredTopK() {
        EnhancedRuleBasedNlpService capped = new EnhancedRuleBasedNlpService(0.99, 2);

        EnhancedNlpResult result = capped.analyzeWithConfidence(
                "таймер 10 напомни купить хлеб громкость на 50", "ru");

        assertEquals("UNKNOWN", result.intent());
        assertEquals(2, result.candidates().size());
        List<String> intents = result.candidates().stream().map(IntentCandidate::intent).toList();
        // Highest-confidence signals in this phrase: change_volume (0.8), add_reminder (0.75).
        assertEquals(List.of("change_volume", "add_reminder"), intents);
    }

    // ---------------------------------------------------------------
    // Entity extraction improvements
    // ---------------------------------------------------------------

    @Test
    void analyzeWithConfidenceParsesTimerInHours() {
        EnhancedNlpResult result = service.analyzeWithConfidence("поставь таймер на 2 часа", "ru");

        assertEquals("set_timer", result.intent());
        assertEquals("2", result.entities().get("amount"));
        assertEquals("hour", result.entities().get("unit"));
    }

    @Test
    void analyzeWithConfidenceExtractsRelativeDateFromReminder() {
        EnhancedNlpResult result = service.analyzeWithConfidence("напомни завтра позвонить маме", "ru");

        assertEquals("add_reminder", result.intent());
        assertEquals("tomorrow", result.entities().get("date"));
        assertTrue(result.entities().get("text").contains("позвонить"));
    }

    @Test
    void analyzeWithConfidenceExtractsClockTimeFromReminder() {
        EnhancedNlpResult result = service.analyzeWithConfidence("напомни в 15:00 позвонить маме", "ru");

        assertEquals("add_reminder", result.intent());
        assertEquals("15:00", result.entities().get("time"));
    }

    @Test
    void analyzeWithConfidenceExtractsDayPartWhenNoExplicitClockTime() {
        EnhancedNlpResult result = service.analyzeWithConfidence("напомни утром позвонить маме", "ru");

        assertEquals("add_reminder", result.intent());
        assertEquals("morning", result.entities().get("dayPart"));
    }
}
