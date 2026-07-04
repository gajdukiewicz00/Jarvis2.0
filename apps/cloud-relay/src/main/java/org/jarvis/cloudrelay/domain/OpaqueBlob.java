package org.jarvis.cloudrelay.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 12 — a stored blob from the relay's perspective.
 *
 * <p>The relay holds opaque bytes and a few wire metadata fields needed
 * to route and expire. It deliberately does NOT have a SyncEnvelope or
 * any other Jarvis-specific schema — that absence is the structural
 * proof the relay cannot interpret personal data.</p>
 */
public final class OpaqueBlob {

    public enum Direction { TO_HOME, TO_DEVICE }

    private final String blobId;
    private final Direction direction;
    private final byte[] payload;
    private final Instant storedAt;

    public OpaqueBlob(Direction direction, byte[] payload, Instant storedAt) {
        this.blobId = "blob-" + UUID.randomUUID();
        this.direction = direction;
        this.payload = payload.clone();
        this.storedAt = storedAt;
    }

    public String blobId() { return blobId; }
    public Direction direction() { return direction; }
    public byte[] payload() { return payload.clone(); }
    public int size() { return payload.length; }
    public Instant storedAt() { return storedAt; }
}
