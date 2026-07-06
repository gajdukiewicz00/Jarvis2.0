package org.jarvis.nlp.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextNormalizerTest {

    @Test
    void normalizeConvertsNumberWordsAndStripsFillersForRussianTimerPhrase() {
        // Regression for finding #26: \b without Pattern.UNICODE_CHARACTER_CLASS never
        // matches boundaries around Cyrillic tokens, so number-word and filler-word
        // replacement used to be a silent no-op for pure-Cyrillic text. Unit
        // normalization (секунд/минут -> sec/min) is intentionally left alone here:
        // RuleBasedNlpService/EnhancedRuleBasedNlpService's timer patterns match the
        // raw Cyrillic unit word directly, so "секунд" stays unconverted.
        assertEquals(
                "поставь таймер на 20 секунд",
                TextNormalizer.normalize("Пожалуйста, поставь таймер на двадцать секунд, ладно?"));
    }

    @Test
    void normalizeConvertsSpokenNumberWordToDigitInExpensePhrase() {
        // Exact failure scenario from finding #26: a spelled-out Russian number must
        // become a digit so the EXPENSE intent pattern's \d+ group can match downstream.
        assertEquals("потратил 5 рублей на кофе", TextNormalizer.normalize("потратил пять рублей на кофе"));
    }

    @Test
    void normalizeKeepsPercentAndRemovesPunctuationNoise() {
        assertEquals("сделай громкость 30%", TextNormalizer.normalize("Сделай громкость 30%!!!"));
    }

    @Test
    void normalizeReturnsEmptyStringForNullInput() {
        assertEquals("", TextNormalizer.normalize(null));
    }
}
