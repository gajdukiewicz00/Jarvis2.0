package org.jarvis.syncservice.domain;

import org.jarvis.sync.crypto.SessionKeys;

import java.time.Instant;
import java.util.Objects;

/**
 * Phase 12 — server-side record of a paired Android device.
 *
 * <p>Holds the device's public identity (Ed25519), the per-pairing
 * X25519 pubkey, the derived ChaCha20-Poly1305 session key, and the
 * opaque {@code routingId} the cloud relay uses to forward blobs.
 * Sessions are immutable: re-pairing the same device produces a new
 * record (different routingId, fresh kex pubkey, fresh session key).</p>
 */
public final class PairedDevice {

    private final String deviceId;          // opaque alias
    private final String deviceLabel;       // human-friendly
    private final byte[] identityPub;       // Ed25519 pubkey, 32 bytes
    private final byte[] devicePubKex;      // X25519 device pubkey, 32 bytes
    private final byte[] serverPubKex;      // X25519 server pubkey for this pairing, 32 bytes
    private final SessionKeys sessionKey;   // 32-byte AEAD key derived via HKDF
    private final String routingId;         // opaque cloud-relay routing key
    private final Instant pairedAt;
    private volatile Instant lastSeen;

    public PairedDevice(String deviceId, String deviceLabel, byte[] identityPub,
                        byte[] devicePubKex, byte[] serverPubKex, SessionKeys sessionKey,
                        String routingId, Instant pairedAt) {
        this.deviceId = Objects.requireNonNull(deviceId);
        this.deviceLabel = deviceLabel != null ? deviceLabel : "unnamed-device";
        this.identityPub = identityPub.clone();
        this.devicePubKex = devicePubKex.clone();
        this.serverPubKex = serverPubKex.clone();
        this.sessionKey = Objects.requireNonNull(sessionKey);
        this.routingId = Objects.requireNonNull(routingId);
        this.pairedAt = pairedAt != null ? pairedAt : Instant.now();
        this.lastSeen = this.pairedAt;
    }

    public String deviceId() { return deviceId; }
    public String deviceLabel() { return deviceLabel; }
    public byte[] identityPub() { return identityPub.clone(); }
    public byte[] devicePubKex() { return devicePubKex.clone(); }
    public byte[] serverPubKex() { return serverPubKex.clone(); }
    public SessionKeys sessionKey() { return sessionKey; }
    public String routingId() { return routingId; }
    public Instant pairedAt() { return pairedAt; }
    public Instant lastSeen() { return lastSeen; }
    public void touch() { this.lastSeen = Instant.now(); }
}
