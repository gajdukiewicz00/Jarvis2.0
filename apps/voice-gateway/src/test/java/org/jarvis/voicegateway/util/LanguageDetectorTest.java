package org.jarvis.voicegateway.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageDetectorTest {

    @Test
    void detectDefaultsToRussianForNullOrBlankInput() {
        assertEquals(LanguageDetector.RUSSIAN, LanguageDetector.detect(null));
        assertEquals(LanguageDetector.RUSSIAN, LanguageDetector.detect(" "));
    }

    @Test
    void detectReturnsRussianWhenCyrillicCharactersArePresent() {
        assertEquals(LanguageDetector.RUSSIAN, LanguageDetector.detect("сделай громче"));
        assertEquals(LanguageDetector.RUSSIAN, LanguageDetector.detect("Jarvis, привет"));
    }

    @Test
    void detectReturnsEnglishWhenOnlyLatinCharactersArePresent() {
        assertEquals(LanguageDetector.ENGLISH, LanguageDetector.detect("make it louder"));
    }
}
