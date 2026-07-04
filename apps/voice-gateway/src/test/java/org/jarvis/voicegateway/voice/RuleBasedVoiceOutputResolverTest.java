package org.jarvis.voicegateway.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuleBasedVoiceOutputResolverTest {

    private RuleBasedVoiceOutputResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RuleBasedVoiceOutputResolver();
        // Loads the real voice-routing-rules.yaml shipped in main/resources.
        resolver.loadRules();
    }

    @Test
    void lookupAssetIdReturnsRuleForKnownActionAndRussianLocale() {
        assertEquals("ru/system/always_at_service_sir", resolver.lookupAssetId("wake_response", "ru-RU"));
    }

    @Test
    void lookupAssetIdIsCaseInsensitiveOnAction() {
        assertEquals("ru/system/always_at_service_sir", resolver.lookupAssetId("WAKE_RESPONSE", "ru"));
    }

    @Test
    void lookupAssetIdDefaultsToRussianLocaleWhenLocaleMissing() {
        assertEquals("ru/system/always_at_service_sir", resolver.lookupAssetId("wake_response", null));
    }

    @Test
    void lookupAssetIdReturnsNullForEnglishLocaleWhenRuleHasNoEnKey() {
        assertNull(resolver.lookupAssetId("wake_response", "en-US"));
    }

    @Test
    void lookupAssetIdReturnsNullForUnknownAction() {
        assertNull(resolver.lookupAssetId("totally_unknown_action", "ru"));
    }

    @Test
    void lookupAssetIdReturnsNullForBlankAction() {
        assertNull(resolver.lookupAssetId("", "ru"));
        assertNull(resolver.lookupAssetId(null, "ru"));
    }

    @Test
    void resolveReturnsPreRecordedModeWhenAssetRuleMatches() {
        VoiceResponse response = resolver.resolve("wake_response", "hello", "ru", null, Map.of());

        assertEquals(VoicePlaybackMode.PRE_RECORDED, response.getMode());
        assertEquals("ru/system/always_at_service_sir", response.getAudioAssetId());
        assertEquals("hello", response.getText());
        assertNull(response.getAudioData());
    }

    @Test
    void resolveFallsBackToTtsWhenNoRuleMatchesAndDefaultModeIsTts() {
        VoiceResponse response = resolver.resolve("totally_unknown_action", "some text", "ru", null, Map.of());

        assertEquals(VoicePlaybackMode.TTS, response.getMode());
        assertEquals("some text", response.getText());
    }

    @Test
    void resolveFallsBackToSilentWhenNoRuleMatchesAndDefaultModeIsSilent() {
        ReflectionTestUtils.setField(resolver, "defaultMode", "SILENT");

        VoiceResponse response = resolver.resolve("totally_unknown_action", "quiet please", "ru", null, Map.of());

        assertEquals(VoicePlaybackMode.SILENT, response.getMode());
        assertEquals("quiet please", response.getText());
    }

}
