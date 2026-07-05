package org.jarvis.voicegateway.health;

import org.jarvis.common.security.ServiceJwtProvider;
import org.jarvis.voicegateway.service.SttService;
import org.jarvis.voicegateway.service.TtsService;
import org.jarvis.voicegateway.voice.VoiceAssetLoader;
import org.jarvis.voicegateway.voice.WavResponseRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Covers VoiceReadinessService's canonicalRecognitionLanguage() and immutableDetails()
 * helpers directly. VoiceReadinessServiceTest covers overallStatus() aggregation and
 * VoiceReadinessServiceComputeSnapshotTest covers the inspectXxx()/currentSnapshot() flow,
 * so this file targets the remaining, smaller helper branches not exercised by either.
 */
class VoiceReadinessServiceLocaleAndDetailsTest {

    private VoiceReadinessService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ConnectionFactory> rabbitProvider = mock(ObjectProvider.class);
        service = new VoiceReadinessService(
                mock(SttService.class),
                mock(TtsService.class),
                mock(VoiceAssetLoader.class),
                mock(WavResponseRegistry.class),
                RestClient.builder(),
                mock(ServiceJwtProvider.class),
                rabbitProvider);
    }

    // ==================== canonicalRecognitionLanguage(String) ====================

    @Test
    void nullLanguageFallsBackToRussianDefaultLanguage() {
        ReflectionTestUtils.setField(service, "defaultLanguage", "ru-RU");

        assertEquals("ru-RU", canonicalLanguage(null));
    }

    @Test
    void blankLanguageFallsBackToConfiguredEnglishDefaultLanguage() {
        ReflectionTestUtils.setField(service, "defaultLanguage", "en-US");

        assertEquals("en-US", canonicalLanguage("   "));
    }

    @Test
    void explicitEnglishLanguageNormalizesToEnUs() {
        ReflectionTestUtils.setField(service, "defaultLanguage", "ru-RU");

        assertEquals("en-US", canonicalLanguage("en_US"));
    }

    @Test
    void explicitNonEnglishLanguageNormalizesToRuRu() {
        ReflectionTestUtils.setField(service, "defaultLanguage", "ru-RU");

        assertEquals("ru-RU", canonicalLanguage("RU-ru"));
    }

    @Test
    void explicitNonEnglishLanguageWithWhitespaceIsTrimmed() {
        ReflectionTestUtils.setField(service, "defaultLanguage", "ru-RU");

        assertEquals("ru-RU", canonicalLanguage("  fr-FR  "));
    }

    private String canonicalLanguage(String language) {
        return (String) ReflectionTestUtils.invokeMethod(service, "canonicalRecognitionLanguage", language);
    }

    // ==================== immutableDetails(Map) ====================

    @Test
    void immutableDetailsReturnsEmptyMapForNullInput() {
        Map<?, ?> result = invokeImmutableDetails(null);

        assertTrue(result.isEmpty());
    }

    @Test
    void immutableDetailsReturnsEmptyMapForEmptyInput() {
        Map<?, ?> result = invokeImmutableDetails(Map.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void immutableDetailsFiltersNullKeysAndValues() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(null, "ignored-because-null-key");
        raw.put("keep", "kept-value");
        raw.put("dropped", null);

        Map<?, ?> result = invokeImmutableDetails(raw);

        assertEquals(Map.of("keep", "kept-value"), result);
    }

    @Test
    void immutableDetailsResultCannotBeModified() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("key", "value");

        Map<Object, Object> result = invokeImmutableDetails(raw);

        assertThrows(UnsupportedOperationException.class, () -> result.put("new", "value"));
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> invokeImmutableDetails(Map<String, Object> details) {
        return (Map<Object, Object>)
                ReflectionTestUtils.invokeMethod(VoiceReadinessService.class, "immutableDetails", details);
    }
}
