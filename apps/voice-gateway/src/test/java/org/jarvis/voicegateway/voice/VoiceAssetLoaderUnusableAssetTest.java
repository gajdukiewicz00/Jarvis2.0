package org.jarvis.voicegateway.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers VoiceAssetLoader's {@code loadFromClasspath()} unusable-asset branch: a manifest entry
 * that resolves to a classpath resource which exists but is not a valid WAV. Reading its bytes
 * succeeds, but {@code CanonicalWavAudio.normalizeToCanonicalWav} throws an
 * {@link IllegalArgumentException} (a RuntimeException), so the loader logs and returns {@code null}
 * instead of propagating. Uses an existing non-WAV classpath resource (the response-registry YAML)
 * so no test resources are added.
 */
class VoiceAssetLoaderUnusableAssetTest {

    private VoiceAssetLoader loader;

    @BeforeEach
    void setUp() {
        loader = new VoiceAssetLoader();
        loader.init();
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadReturnsNullWhenAssetResourceExistsButIsNotAValidWav() {
        Map<String, String> manifestResources =
                (Map<String, String>) ReflectionTestUtils.getField(loader, "manifestResources");
        // Point a synthetic asset id at a real, non-WAV classpath resource.
        manifestResources.put("bad/unusable", "voice-response-registry.yaml");

        assertNull(loader.load("bad/unusable"),
                "an existing-but-non-WAV asset must resolve to null via the RuntimeException branch");
        assertFalse(loader.hasAsset("bad/unusable"));
    }
}
