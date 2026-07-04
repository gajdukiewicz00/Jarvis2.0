package org.jarvis.syncservice.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Phase 12 — application-level replay protection on top of AEAD nonces.
 *
 * <p>ChaCha20-Poly1305 fails authentication on nonce reuse <em>only</em>
 * if the same key+nonce is used to encrypt two different plaintexts —
 * but a network attacker who replays an unmodified blob would not be
 * caught by the AEAD itself. This cache rejects the second sighting of
 * the same {@code (deviceId, nonce)} pair within a sliding window.</p>
 */
@Component
public class ReplayCache {

    private final Cache<String, Boolean> seen;
    private final int perDeviceCap;

    public ReplayCache(SyncServiceProperties props) {
        this.perDeviceCap = props.getReplayCacheSizePerDevice();
        // Total cap = per-device cap × ~16 paired devices upper bound; coarse
        // cap is fine because real volume per device per hour is tiny.
        this.seen = Caffeine.newBuilder()
                .maximumSize((long) perDeviceCap * 16)
                .expireAfterWrite(Duration.ofHours(24))
                .build();
    }

    /** @return true if the nonce was unseen and is now recorded; false on replay. */
    public boolean recordIfUnseen(String deviceId, String nonceB64) {
        String key = deviceId + "|" + nonceB64;
        // Atomic check-and-record: putIfAbsent on the backing ConcurrentMap closes the
        // TOCTOU window where two concurrent replays of the same nonce both pass.
        return seen.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }

    public int size() {
        return (int) seen.estimatedSize();
    }
}
