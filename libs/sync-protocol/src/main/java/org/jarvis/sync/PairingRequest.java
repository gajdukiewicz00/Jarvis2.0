package org.jarvis.sync;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Phase 12 — Android → sync-service pairing request.
 *
 * <p>Sent over a trusted LAN channel (or out-of-band QR). The phone
 * presents its long-lived Ed25519 identity pubkey, an X25519 ephemeral
 * (per-pairing) kex pubkey, and a signature over a server-issued nonce
 * concatenated with the kex pubkey. The server verifies the signature
 * with {@code identityPubB64}, derives the shared secret with its own
 * X25519 keypair, and persists the pairing.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class PairingRequest {

    private final String deviceLabel;          // human-friendly, e.g. "Pixel 9 — Misha"
    private final String identityPubB64;       // Ed25519 long-lived pubkey (32 bytes, b64url)
    private final String kexPubB64;            // X25519 pairing pubkey (32 bytes, b64url)
    private final String pairingNonceB64;      // server-issued nonce echoed back
    private final String identitySigB64;       // Ed25519 sig over (pairingNonceB64 || kexPubB64)

    @JsonCreator
    public PairingRequest(
            @JsonProperty("deviceLabel") String deviceLabel,
            @JsonProperty("identityPubB64") String identityPubB64,
            @JsonProperty("kexPubB64") String kexPubB64,
            @JsonProperty("pairingNonceB64") String pairingNonceB64,
            @JsonProperty("identitySigB64") String identitySigB64) {
        this.deviceLabel = deviceLabel != null ? deviceLabel : "unnamed-device";
        this.identityPubB64 = Objects.requireNonNull(identityPubB64, "identityPubB64");
        this.kexPubB64 = Objects.requireNonNull(kexPubB64, "kexPubB64");
        this.pairingNonceB64 = Objects.requireNonNull(pairingNonceB64, "pairingNonceB64");
        this.identitySigB64 = Objects.requireNonNull(identitySigB64, "identitySigB64");
    }

    public String getDeviceLabel() { return deviceLabel; }
    public String getIdentityPubB64() { return identityPubB64; }
    public String getKexPubB64() { return kexPubB64; }
    public String getPairingNonceB64() { return pairingNonceB64; }
    public String getIdentitySigB64() { return identitySigB64; }
}
