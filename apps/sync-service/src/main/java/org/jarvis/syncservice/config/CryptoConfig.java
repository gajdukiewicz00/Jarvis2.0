package org.jarvis.syncservice.config;

import org.jarvis.sync.crypto.SyncCrypto;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 12 — registers the singleton {@link SyncCrypto} so callers can
 * inject the same instance everywhere (cheap, stateless, thread-safe).
 */
@Configuration
@EnableConfigurationProperties(SyncServiceProperties.class)
public class CryptoConfig {

    @Bean
    public SyncCrypto syncCrypto() {
        return new SyncCrypto();
    }
}
