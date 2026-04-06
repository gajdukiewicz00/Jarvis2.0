package org.jarvis.voicegateway.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VoiceAssetLoaderTest {

    private VoiceAssetLoader loader;

    @BeforeEach
    void setUp() {
        loader = new VoiceAssetLoader();
        loader.init();
    }

    @Test
    void loadsActiveAssetsFromManifest() {
        assertEquals(95, loader.getActiveAssetCount());
    }

    @Test
    void loadsIntegratedLegacyAssetBytes() {
        byte[] data = loader.load("ru/system/yes_sir");

        assertNotNull(data);
        assertTrue(data.length > 0);
        assertTrue(loader.hasAsset("ru/system/yes_sir"));
    }

    @Test
    void returnsNullForMissingAsset() {
        assertNull(loader.load("ru/system/does_not_exist"));
        assertFalse(loader.hasAsset("ru/system/does_not_exist"));
    }
}
