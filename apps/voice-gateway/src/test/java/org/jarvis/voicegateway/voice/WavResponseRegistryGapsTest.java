package org.jarvis.voicegateway.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers WavResponseRegistry branches not already exercised by WavResponseRegistryTest:
 * getReferencedAssetIds(), the null/blank/unknown-key branches of lookupAssetId()/
 * lookupText(), the ResponseProfile.assetFor()/textFor() locale-fallback chain
 * (exact match -> "ru" -> "en" -> first available), and the static stringValue()/
 * stringMap() helpers.
 */
class WavResponseRegistryGapsTest {

    private WavResponseRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WavResponseRegistry();
        registry.load();
    }

    // ==================== getReferencedAssetIds() ====================

    @Test
    void getReferencedAssetIdsReturnsNonEmptyImmutableSet() {
        Set<String> ids = registry.getReferencedAssetIds();

        assertTrue(ids.size() > 0);
        assertThrows(UnsupportedOperationException.class, () -> ids.add("new-id"));
    }

    // ==================== lookupAssetId() / lookupText() ====================

    @Test
    void lookupsReturnNullForNullOrBlankKey() {
        assertNull(registry.lookupAssetId(null, "ru"));
        assertNull(registry.lookupAssetId("   ", "ru"));
        assertNull(registry.lookupText(null, "ru"));
        assertNull(registry.lookupText("   ", "ru"));
    }

    @Test
    void lookupsReturnNullForUnknownResponseKey() {
        assertNull(registry.lookupAssetId("this_key_does_not_exist", "ru"));
        assertNull(registry.lookupText("this_key_does_not_exist", "ru"));
    }

    // ==================== ResponseProfile.assetFor()/textFor() locale fallback ====================

    @Test
    void localizedValueReturnsNullWhenMapIsEmpty() {
        WavResponseRegistry.ResponseProfile profile = new WavResponseRegistry.ResponseProfile("k", null, null);

        assertNull(profile.assetFor("ru"));
        assertNull(profile.textFor("en-US"));
    }

    @Test
    void localizedValueReturnsExactNormalizedLocaleMatch() {
        WavResponseRegistry.ResponseProfile profile =
                new WavResponseRegistry.ResponseProfile("k", Map.of(), Map.of("en", "Hello"));

        assertEquals("Hello", profile.textFor("en-US"));
    }

    @Test
    void localizedValueFallsBackToRuWhenNormalizedLocaleMissing() {
        WavResponseRegistry.ResponseProfile profile =
                new WavResponseRegistry.ResponseProfile("k", Map.of(), Map.of("ru", "Привет"));

        assertEquals("Привет", profile.textFor("en-US"));
    }

    @Test
    void localizedValueFallsBackToEnWhenNormalizedAndRuMissing() {
        WavResponseRegistry.ResponseProfile profile =
                new WavResponseRegistry.ResponseProfile("k", Map.of(), Map.of("en", "Hi"));

        assertEquals("Hi", profile.textFor("fr-FR"));
    }

    @Test
    void localizedValueFallsBackToFirstAvailableWhenNoRecognizedLocaleMatches() {
        WavResponseRegistry.ResponseProfile profile =
                new WavResponseRegistry.ResponseProfile("k", Map.of(), Map.of("de", "Hallo"));

        assertEquals("Hallo", profile.textFor("fr-FR"));
    }

    @Test
    void localizedValueSkipsBlankValuesAlongTheFallbackChain() {
        Map<String, String> text = new LinkedHashMap<>();
        text.put("ru", "");
        text.put("en", "Hi");
        WavResponseRegistry.ResponseProfile profile = new WavResponseRegistry.ResponseProfile("k", Map.of(), text);

        assertEquals("Hi", profile.textFor(null));
    }

    @Test
    void assetForDelegatesToAssetsMapWithSameFallbackChain() {
        WavResponseRegistry.ResponseProfile profile =
                new WavResponseRegistry.ResponseProfile("k", Map.of("ru", "ru/system/yes_sir"), Map.of());

        assertEquals("ru/system/yes_sir", profile.assetFor("en-US"));
    }

    // ==================== static helpers ====================

    @Test
    void stringValueTrimsAndTreatsBlankAsNull() {
        assertNull(invokeStatic("stringValue", new Object[] {null}));
        assertNull(invokeStatic("stringValue", "   "));
        assertEquals("hi", invokeStatic("stringValue", "  hi  "));
        assertEquals("42", invokeStatic("stringValue", 42));
    }

    @Test
    void stringMapFiltersNonMapAndNullEntries() {
        assertTrue(((Map<?, ?>) invokeStatic("stringMap", "not-a-map")).isEmpty());

        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("ru", "привет");
        raw.put(null, "ignored-null-key");
        raw.put("en", null);

        Map<?, ?> result = (Map<?, ?>) invokeStatic("stringMap", raw);

        assertEquals(Map.of("ru", "привет"), result);
    }

    private Object invokeStatic(String method, Object... args) {
        return ReflectionTestUtils.invokeMethod(WavResponseRegistry.class, method, args);
    }
}
