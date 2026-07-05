package org.jarvis.voicegateway.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers VoiceAssetLoader branches not already exercised by VoiceAssetLoaderTest:
 * getActiveAssetIds(), prewarm(), the load()/hasAsset() null-and-blank guard branches,
 * the byte[] caching behavior of load(), and the stringValue() helper.
 */
class VoiceAssetLoaderGapsTest {

    private VoiceAssetLoader loader;

    @BeforeEach
    void setUp() {
        loader = new VoiceAssetLoader();
        loader.init();
    }

    @Test
    void getActiveAssetIdsReturnsSortedNonEmptyListMatchingActiveCount() {
        List<String> ids = loader.getActiveAssetIds();

        assertEquals(loader.getActiveAssetCount(), ids.size());
        assertTrue(ids.size() > 0);
        List<String> sorted = ids.stream().sorted().toList();
        assertEquals(sorted, ids);
    }

    @Test
    void prewarmLoadsGivenAssetIdsWithoutThrowingForUnknownIds() {
        loader.prewarm("ru/system/yes_sir", "does/not/exist", "");

        assertTrue(loader.hasAsset("ru/system/yes_sir"));
        assertFalse(loader.hasAsset("does/not/exist"));
    }

    @Test
    void loadReturnsNullForNullOrBlankAssetId() {
        assertNull(loader.load(null));
        assertNull(loader.load("   "));
    }

    @Test
    void hasAssetReturnsFalseForNullOrBlankAssetId() {
        assertFalse(loader.hasAsset(null));
        assertFalse(loader.hasAsset("   "));
    }

    @Test
    void loadCachesResultAndReturnsSameArrayInstanceOnRepeatedCalls() {
        byte[] first = loader.load("ru/system/yes_sir");
        byte[] second = loader.load("ru/system/yes_sir");

        assertSame(first, second);
    }

    @Test
    void stringValueTrimsAndTreatsBlankAsNull() {
        assertNull(invokeStringValue(null));
        assertNull(invokeStringValue("   "));
        assertEquals("hi", invokeStringValue("  hi  "));
        assertEquals("42", invokeStringValue(42));
    }

    private Object invokeStringValue(Object value) {
        return ReflectionTestUtils.invokeMethod(VoiceAssetLoader.class, "stringValue", value);
    }
}
