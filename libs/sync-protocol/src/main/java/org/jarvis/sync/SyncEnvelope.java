package org.jarvis.sync;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * Phase 12 — wire envelope sent from Android to cloud-relay (and forwarded
 * to sync-service) or directly to sync-service on the LAN.
 *
 * <p>The envelope is opaque to the relay: only {@code routingId},
 * {@code senderDeviceId}, {@code nonce} and {@code ciphertext} are
 * visible. The plaintext (a {@link SyncPayload}) is sealed under the
 * device↔server session key derived during pairing.</p>
 *
 * <p>Wire format is stable. Add fields, never rename. Consumers ignore
 * unknown fields.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class SyncEnvelope {

    /** Wire-format major version. Increment only on breaking changes. */
    public static final int CURRENT_VERSION = 1;

    private final int version;
    /** Opaque to the relay; assigned per pairing — points at a destination sync-service. */
    private final String routingId;
    /** Opaque alias of the sending device (hash of its Ed25519 pubkey). */
    private final String senderDeviceId;
    /** Base64 (URL, no padding) ChaCha20-Poly1305 12-byte nonce. */
    private final String nonceB64;
    /** Base64 (URL, no padding) ciphertext+tag (Poly1305 tag is appended by JCE). */
    private final String ciphertextB64;
    /** Client-side timestamp; not trusted, used only for ordering hints. */
    private final Instant occurredAtClient;

    @JsonCreator
    public SyncEnvelope(
            @JsonProperty("version") int version,
            @JsonProperty("routingId") String routingId,
            @JsonProperty("senderDeviceId") String senderDeviceId,
            @JsonProperty("nonceB64") String nonceB64,
            @JsonProperty("ciphertextB64") String ciphertextB64,
            @JsonProperty("occurredAtClient") Instant occurredAtClient) {
        this.version = version == 0 ? CURRENT_VERSION : version;
        this.routingId = Objects.requireNonNull(routingId, "routingId");
        this.senderDeviceId = Objects.requireNonNull(senderDeviceId, "senderDeviceId");
        this.nonceB64 = Objects.requireNonNull(nonceB64, "nonceB64");
        this.ciphertextB64 = Objects.requireNonNull(ciphertextB64, "ciphertextB64");
        this.occurredAtClient = occurredAtClient != null ? occurredAtClient : Instant.now();
    }

    public int getVersion() { return version; }
    public String getRoutingId() { return routingId; }
    public String getSenderDeviceId() { return senderDeviceId; }
    public String getNonceB64() { return nonceB64; }
    public String getCiphertextB64() { return ciphertextB64; }
    public Instant getOccurredAtClient() { return occurredAtClient; }
}
