package org.jarvis.sync.crypto;

import java.util.Arrays;

/**
 * Phase 12 — derived AEAD key for one paired device session.
 *
 * <p>The same {@code key} is used for both directions (device→server and
 * server→device). Replay is prevented by the cryptographic nonce + an
 * application-level seen-nonce cache in sync-service.</p>
 */
public final class SessionKeys {

    private final byte[] key; // 32 bytes for ChaCha20-Poly1305

    public SessionKeys(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("session key must be 32 bytes");
        }
        this.key = key.clone();
    }

    public byte[] key() {
        return key.clone();
    }

    public void wipe() {
        Arrays.fill(key, (byte) 0);
    }
}
