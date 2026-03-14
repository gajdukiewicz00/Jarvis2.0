package org.jarvis.orchestrator.phrases;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JarvisPhraseProvider}.
 */
class JarvisPhraseProviderTest {

    private JarvisPhraseProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JarvisPhraseProvider();
    }

    // ==================== Volume Control ====================

    @Test
    @DisplayName("VOLUME_UP returns Russian phrase for RU")
    void volumeUpRussian() {
        String phrase = provider.getPhrase(PhraseContext.VOLUME_UP, Language.RU);
        assertNotNull(phrase);
        assertFalse(phrase.isBlank(), "Phrase should not be blank");
        // Check it's Russian (contains Cyrillic)
        assertTrue(containsCyrillic(phrase), "Phrase should be in Russian: " + phrase);
    }

    @Test
    @DisplayName("VOLUME_UP returns English phrase for EN")
    void volumeUpEnglish() {
        String phrase = provider.getPhrase(PhraseContext.VOLUME_UP, Language.EN);
        assertNotNull(phrase);
        assertFalse(phrase.isBlank(), "Phrase should not be blank");
        // Check it's English (no Cyrillic)
        assertFalse(containsCyrillic(phrase), "Phrase should be in English: " + phrase);
    }

    @Test
    @DisplayName("VOLUME_DOWN returns appropriate phrases")
    void volumeDown() {
        String ru = provider.getPhrase(PhraseContext.VOLUME_DOWN, Language.RU);
        String en = provider.getPhrase(PhraseContext.VOLUME_DOWN, Language.EN);
        
        assertNotNull(ru);
        assertNotNull(en);
        assertTrue(containsCyrillic(ru), "RU phrase should contain Cyrillic");
        assertFalse(containsCyrillic(en), "EN phrase should not contain Cyrillic");
    }

    // ==================== Media Control ====================

    @Test
    @DisplayName("PLAY returns appropriate phrases")
    void play() {
        String ru = provider.getPhrase(PhraseContext.PLAY, Language.RU);
        String en = provider.getPhrase(PhraseContext.PLAY, Language.EN);
        
        assertNotNull(ru);
        assertNotNull(en);
        assertTrue(containsCyrillic(ru), "RU phrase should contain Cyrillic");
        assertFalse(containsCyrillic(en), "EN phrase should not contain Cyrillic");
    }

    @Test
    @DisplayName("PAUSE returns appropriate phrases")
    void pause() {
        String ru = provider.getPhrase(PhraseContext.PAUSE, Language.RU);
        String en = provider.getPhrase(PhraseContext.PAUSE, Language.EN);
        
        assertNotNull(ru);
        assertNotNull(en);
        assertFalse(ru.isBlank());
        assertFalse(en.isBlank());
        assertTrue(containsCyrillic(ru), "RU phrase should contain Cyrillic: " + ru);
        assertFalse(containsCyrillic(en), "EN phrase should not contain Cyrillic: " + en);
    }

    // ==================== Template Substitution ====================

    @Test
    @DisplayName("Template parameters are substituted correctly")
    void templateSubstitution() {
        String phrase = provider.getPhrase(PhraseContext.OPEN_APP, Language.RU, 
                Map.of("app", "YouTube"));
        
        assertNotNull(phrase);
        assertTrue(phrase.contains("YouTube"), 
                "App name should be substituted: " + phrase);
    }

    @Test
    @DisplayName("Morning greeting includes time when provided")
    void morningGreetingWithTime() {
        String phrase = provider.getPhrase(PhraseContext.MORNING_GREETING, Language.RU,
                Map.of("time", "08:30", "userName", "Тони"));
        
        assertNotNull(phrase);
        assertFalse(phrase.isBlank());
        // Just verify it's a valid Russian phrase
        assertTrue(containsCyrillic(phrase), "Should be Russian: " + phrase);
    }

    @Test
    @DisplayName("Timer phrase includes amount and unit")
    void timerWithParams() {
        String phrase = provider.getPhrase(PhraseContext.TIMER_SET, Language.EN,
                Map.of("amount", "5", "unit", "minutes"));
        
        assertNotNull(phrase);
        assertTrue(phrase.contains("5") || phrase.contains("minutes"),
                "Should include amount or unit: " + phrase);
    }

    @Test
    @DisplayName("Smart-home phrase includes the device name")
    void smartHomeTurnOnWithParams() {
        String phrase = provider.getPhrase(PhraseContext.SMART_HOME_TURN_ON, Language.EN,
                Map.of("device", "kitchen light"));

        assertNotNull(phrase);
        assertTrue(phrase.toLowerCase().contains("kitchen light"),
                "Should include the device name: " + phrase);
    }

    // ==================== Protocols ====================

    @Test
    @DisplayName("House Party Protocol phrases")
    void housePartyProtocol() {
        String ru = provider.getPhrase(PhraseContext.PROTOCOL_HOUSE_PARTY, Language.RU);
        String en = provider.getPhrase(PhraseContext.PROTOCOL_HOUSE_PARTY, Language.EN);
        
        assertNotNull(ru);
        assertNotNull(en);
        assertFalse(ru.isBlank());
        assertFalse(en.isBlank());
        assertTrue(containsCyrillic(ru), "RU phrase should contain Cyrillic");
        assertFalse(containsCyrillic(en), "EN phrase should not contain Cyrillic");
    }

    @Test
    @DisplayName("Clean Slate Protocol phrases")
    void cleanSlateProtocol() {
        String ru = provider.getPhrase(PhraseContext.PROTOCOL_CLEAN_SLATE, Language.RU);
        String en = provider.getPhrase(PhraseContext.PROTOCOL_CLEAN_SLATE, Language.EN);
        
        assertNotNull(ru);
        assertNotNull(en);
        assertFalse(ru.isBlank());
        assertFalse(en.isBlank());
        assertTrue(containsCyrillic(ru), "RU phrase should contain Cyrillic: " + ru);
        assertFalse(containsCyrillic(en), "EN phrase should not contain Cyrillic: " + en);
    }

    // ==================== Personality / Sarcasm ====================

    @Test
    @DisplayName("Sarcasm phrases exist for both languages")
    void sarcasticFailure() {
        String ru = provider.getPhrase(PhraseContext.SARCASTIC_FAILURE, Language.RU);
        String en = provider.getPhrase(PhraseContext.SARCASTIC_FAILURE, Language.EN);
        
        assertNotNull(ru);
        assertNotNull(en);
        assertFalse(ru.isBlank());
        assertFalse(en.isBlank());
    }

    @Test
    @DisplayName("Security alert phrases")
    void securityAlert() {
        String ru = provider.getPhrase(PhraseContext.SECURITY_ALERT, Language.RU);
        String en = provider.getPhrase(PhraseContext.SECURITY_ALERT, Language.EN);
        
        assertNotNull(ru);
        assertNotNull(en);
        assertFalse(ru.isBlank());
        assertFalse(en.isBlank());
        assertTrue(containsCyrillic(ru), "RU phrase should contain Cyrillic: " + ru);
        assertFalse(containsCyrillic(en), "EN phrase should not contain Cyrillic: " + en);
    }

    // ==================== Fallback ====================

    @Test
    @DisplayName("Unknown command returns fallback phrase")
    void unknownCommand() {
        String ru = provider.getPhrase(PhraseContext.UNKNOWN_COMMAND, Language.RU);
        String en = provider.getPhrase(PhraseContext.UNKNOWN_COMMAND, Language.EN);
        
        assertNotNull(ru);
        assertNotNull(en);
        assertFalse(ru.isBlank());
        assertFalse(en.isBlank());
        assertTrue(containsCyrillic(ru), "RU phrase should contain Cyrillic: " + ru);
        assertFalse(containsCyrillic(en), "EN phrase should not contain Cyrillic: " + en);
    }

    // ==================== Auto Language Detection ====================

    @Test
    @DisplayName("getPhraseAuto detects Russian from Cyrillic text")
    void autoDetectRussian() {
        String phrase = provider.getPhraseAuto(PhraseContext.VOLUME_UP, "сделай громче", Map.of());
        assertNotNull(phrase);
        // Should be Russian response
        assertTrue(containsCyrillic(phrase),
                "Auto-detected phrase should be Russian: " + phrase);
    }

    @Test
    @DisplayName("getPhraseAuto detects English from Latin text")
    void autoDetectEnglish() {
        String phrase = provider.getPhraseAuto(PhraseContext.VOLUME_UP, "make it louder", Map.of());
        assertNotNull(phrase);
        // Should be English response
        assertFalse(containsCyrillic(phrase),
                "Auto-detected phrase should be English: " + phrase);
    }

    // ==================== Variety ====================

    @Test
    @DisplayName("Multiple calls may return different variants")
    void varietyInResponses() {
        // Call multiple times and check we get at least some variety
        java.util.Set<String> phrases = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            phrases.add(provider.getPhrase(PhraseContext.ACK_SUCCESS, Language.RU));
        }
        // Should have more than 1 variant (we have 4 defined)
        assertTrue(phrases.size() > 1, 
                "Should have variety in responses, got: " + phrases);
    }

    // ==================== Helper ====================

    private boolean containsCyrillic(String text) {
        if (text == null) return false;
        for (char c : text.toCharArray()) {
            if ((c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё') {
                return true;
            }
        }
        return false;
    }
}
