package org.jarvis.voicegateway.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

/**
 * Configuration for TTS caching to reduce API calls.
 */
@Configuration
@EnableCaching
public class TtsConfig {

    @Bean
    public ConcurrentMapCacheManager cacheManager() {
        return new ConcurrentMapCacheManager("tts");
    }
}
