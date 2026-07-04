package org.jarvis.syncservice.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.time.Duration;
import java.util.Optional;

/**
 * Phase 12 — short-lived store of pairing-init nonces.
 *
 * <p>Each {@code POST /pairing/init} hands the device a fresh server
 * kex keypair; the public half goes back in the response, the private
 * half is parked here (keyed by the issued nonce) until the device
 * comes back with {@code POST /pairing/complete}. Entries are evicted
 * automatically after {@code pairing-nonce-ttl-seconds} so an
 * abandoned pairing does not retain a server private key indefinitely.</p>
 *
 * <p>Cache is intentionally process-local; pairing is a single
 * in-flight handshake, not a persistent state.</p>
 */
@Component
public class PairingNonceStore {

    private final Cache<String, KeyPair> cache;

    public PairingNonceStore(SyncServiceProperties props) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.getPairingNonceTtlSeconds()))
                .maximumSize(1024)
                .build();
    }

    public void put(String nonceB64, KeyPair serverKex) {
        cache.put(nonceB64, serverKex);
    }

    /** Single-use lookup — invalidates the entry on hit so a nonce can never be re-used. */
    public Optional<KeyPair> consume(String nonceB64) {
        // Atomic remove: a nonce can be consumed by exactly one /pairing/complete,
        // even under concurrent requests (closes the get-then-invalidate TOCTOU).
        return Optional.ofNullable(cache.asMap().remove(nonceB64));
    }
}
