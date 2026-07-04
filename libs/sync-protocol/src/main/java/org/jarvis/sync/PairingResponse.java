package org.jarvis.sync;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Phase 12 — sync-service → Android response after a successful pairing.
 *
 * <p>The server returns its X25519 kex pubkey (so the phone can derive the
 * same shared secret on its side), the opaque {@code routingId} the cloud
 * relay will use to forward blobs, and the {@code senderDeviceId} alias
 * the server assigned to this device.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PairingResponse {

    private final String serverKexPubB64;     // X25519 server pairing pubkey (32 bytes, b64url)
    private final String routingId;           // opaque routing key for cloud-relay
    private final String senderDeviceId;      // opaque alias the server gave this device
    private final Instant pairedAt;

    @JsonCreator
    public PairingResponse(
            @JsonProperty("serverKexPubB64") String serverKexPubB64,
            @JsonProperty("routingId") String routingId,
            @JsonProperty("senderDeviceId") String senderDeviceId,
            @JsonProperty("pairedAt") Instant pairedAt) {
        this.serverKexPubB64 = serverKexPubB64;
        this.routingId = routingId;
        this.senderDeviceId = senderDeviceId;
        this.pairedAt = pairedAt != null ? pairedAt : Instant.now();
    }

    public String getServerKexPubB64() { return serverKexPubB64; }
    public String getRoutingId() { return routingId; }
    public String getSenderDeviceId() { return senderDeviceId; }
    public Instant getPairedAt() { return pairedAt; }
}
