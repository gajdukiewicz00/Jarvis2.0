package org.jarvis.voicegateway.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WavResponseRegistryTest {

    private WavResponseRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WavResponseRegistry();
        registry.load();
    }

    @Test
    void loadsResponseProfilesFromYaml() {
        assertTrue(registry.getLoadedProfileCount() >= 100);
    }

    @Test
    void resolvesAssetIdByResponseKey() {
        assertEquals("ru/assistant/loading_sir", registry.lookupAssetId("loading_sir", "ru-RU"));
        assertEquals("Загружаю, сэр.", registry.lookupText("loading_sir", "ru-RU"));
    }

    @Test
    void resolvesNewlyRegisteredLegacyDiagnosticsAsset() {
        assertEquals("ru/diagnostics/internet_check", registry.lookupAssetId("internet_check", "ru-RU"));
        assertEquals("Проверяю скорость интернета.", registry.lookupText("internet_check", "ru-RU"));
    }
}
