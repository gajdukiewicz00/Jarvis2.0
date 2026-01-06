package org.jarvis.orchestrator.phrases;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LanguageDetector}.
 */
class LanguageDetectorTest {

    @Test
    @DisplayName("Detects Russian when text contains Cyrillic")
    void detectsRussianForCyrillic() {
        assertEquals(Language.RU, LanguageDetector.detect("сделай громче"));
        assertEquals(Language.RU, LanguageDetector.detect("привет"));
        assertEquals(Language.RU, LanguageDetector.detect("увеличь громкость"));
        assertEquals(Language.RU, LanguageDetector.detect("Доброе утро"));
    }

    @Test
    @DisplayName("Detects English when text has no Cyrillic")
    void detectsEnglishForLatin() {
        assertEquals(Language.EN, LanguageDetector.detect("make it louder"));
        assertEquals(Language.EN, LanguageDetector.detect("hello"));
        assertEquals(Language.EN, LanguageDetector.detect("volume up"));
        assertEquals(Language.EN, LanguageDetector.detect("Good morning"));
    }

    @Test
    @DisplayName("Detects Russian for mixed text with Cyrillic")
    void detectsRussianForMixedText() {
        assertEquals(Language.RU, LanguageDetector.detect("открой YouTube"));
        assertEquals(Language.RU, LanguageDetector.detect("включи Netflix"));
    }

    @Test
    @DisplayName("Returns Russian for null or empty text")
    void defaultsToRussianForNullOrEmpty() {
        assertEquals(Language.RU, LanguageDetector.detect(null));
        assertEquals(Language.RU, LanguageDetector.detect(""));
        assertEquals(Language.RU, LanguageDetector.detect("   "));
    }

    @Test
    @DisplayName("Handles special characters correctly")
    void handlesSpecialCharacters() {
        assertEquals(Language.EN, LanguageDetector.detect("123"));
        assertEquals(Language.EN, LanguageDetector.detect("!!!"));
        assertEquals(Language.EN, LanguageDetector.detect("@#$%"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ёжик", "ёлка", "ЁМКОСТЬ"})
    @DisplayName("Handles Cyrillic Ё/ё correctly")
    void handlesCyrillicYo(String text) {
        assertEquals(Language.RU, LanguageDetector.detect(text));
    }

    @Test
    @DisplayName("getCyrillicRatio returns correct ratio")
    void getCyrillicRatioWorks() {
        assertEquals(1.0, LanguageDetector.getCyrillicRatio("привет"), 0.01);
        assertEquals(0.0, LanguageDetector.getCyrillicRatio("hello"), 0.01);
        // "открой YouTube" has 6 Cyrillic letters (о, т, к, р, о, й) and 7 Latin (Y, o, u, T, u, b, e)
        // So ratio should be 6/13 ≈ 0.46
        assertTrue(LanguageDetector.getCyrillicRatio("открой YouTube") > 0.4,
                "Ratio should be > 0.4: " + LanguageDetector.getCyrillicRatio("открой YouTube"));
        assertEquals(0.0, LanguageDetector.getCyrillicRatio(null));
        assertEquals(0.0, LanguageDetector.getCyrillicRatio(""));
    }
}

