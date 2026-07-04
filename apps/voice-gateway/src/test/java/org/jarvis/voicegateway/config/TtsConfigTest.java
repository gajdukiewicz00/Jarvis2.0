package org.jarvis.voicegateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsConfigTest {

    @Test
    void cacheManagerRegistersTtsCache() {
        TtsConfig config = new TtsConfig();

        ConcurrentMapCacheManager cacheManager = config.cacheManager();

        assertNotNull(cacheManager);
        assertTrue(cacheManager.getCacheNames().contains("tts"));
    }
}
