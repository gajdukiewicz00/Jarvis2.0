package org.jarvis.voicegateway.voice;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers WavResponseRegistry.getReferencedAssetIds()'s blank/null-value removal branch, which the
 * real registry data never triggers because every shipped asset value is non-blank. Injects a
 * profile map containing a blank asset value so the {@code removeIf(assetId -> assetId == null ||
 * assetId.isBlank())} predicate actually removes an entry, and asserts the returned set is the
 * de-duplicated, immutable set of non-blank asset ids.
 */
class WavResponseRegistryReferencedAssetsTest {

    @Test
    void getReferencedAssetIdsSkipsBlankValuesAndReturnsImmutableDedupedSet() {
        WavResponseRegistry registry = new WavResponseRegistry();

        Map<String, String> assetsWithBlank = new LinkedHashMap<>();
        assetsWithBlank.put("ru", "ru/system/yes_sir");
        assetsWithBlank.put("en", "   "); // blank -> must be filtered out
        WavResponseRegistry.ResponseProfile withBlank =
                new WavResponseRegistry.ResponseProfile("k1", assetsWithBlank, Map.of());

        // Duplicate asset id across two profiles -> must collapse to a single entry.
        WavResponseRegistry.ResponseProfile duplicate =
                new WavResponseRegistry.ResponseProfile("k2", Map.of("ru", "ru/system/yes_sir"), Map.of());
        WavResponseRegistry.ResponseProfile distinct =
                new WavResponseRegistry.ResponseProfile("k3", Map.of("ru", "ru/system/no_sir"), Map.of());

        Map<String, WavResponseRegistry.ResponseProfile> profiles = new LinkedHashMap<>();
        profiles.put("k1", withBlank);
        profiles.put("k2", duplicate);
        profiles.put("k3", distinct);
        ReflectionTestUtils.setField(registry, "profiles", profiles);

        Set<String> ids = registry.getReferencedAssetIds();

        assertEquals(Set.of("ru/system/yes_sir", "ru/system/no_sir"), ids);
        assertFalse(ids.contains("   "));
        assertEquals(3, registry.getLoadedProfileCount());
        assertThrows(UnsupportedOperationException.class, () -> ids.add("mutation-should-fail"));
    }

    @Test
    void getReferencedAssetIdsIsEmptyWhenNoProfilesAreLoaded() {
        WavResponseRegistry registry = new WavResponseRegistry();
        ReflectionTestUtils.setField(registry, "profiles", Map.of());

        assertTrue(registry.getReferencedAssetIds().isEmpty());
        assertEquals(0, registry.getLoadedProfileCount());
    }
}
