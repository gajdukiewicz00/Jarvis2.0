package org.jarvis.nlp.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextNormalizerTest {

    @Test
    void normalizeCurrentlyOnlyLowercasesAndStripsPunctuationForRussianTimerPhrase() {
        assertEquals(
                "пожалуйста поставь таймер на двадцать секунд ладно",
                TextNormalizer.normalize("Пожалуйста, поставь таймер на двадцать секунд, ладно?"));
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
