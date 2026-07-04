package org.jarvis.syncservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jarvis.sync.PairingRequest;
import org.jarvis.sync.SyncEnvelope;
import org.jarvis.sync.SyncPayload;
import org.jarvis.sync.crypto.SessionKeys;
import org.jarvis.sync.crypto.SyncCrypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;

/**
 * Test helper that performs the device-side half of the pairing
 * handshake and seal step, so tests can exercise sync-service end-to-end
 * without an actual Android device.
 */
public final class SimulatedDevice {

    private final SyncCrypto crypto = new SyncCrypto();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final KeyPair identity = crypto.generateIdentityKeyPair();
    private KeyPair kex;
    private byte[] serverKexPub;
    private SessionKeys sessionKey;
    private String routingId;
    private String deviceId;

    public PairingRequest buildPairingRequest(String label, String pairingNonceB64, String serverKexPubB64) {
        kex = crypto.generateKexKeyPair();
        serverKexPub = SyncCrypto.unb64(serverKexPubB64);
        byte[] kexPubRaw = crypto.encodeX25519Pub(kex.getPublic());
        String kexPubB64 = SyncCrypto.b64(kexPubRaw);
        byte[] message = ByteBuffer.allocate(pairingNonceB64.length() + kexPubB64.length())
                .put(pairingNonceB64.getBytes(StandardCharsets.UTF_8))
                .put(kexPubB64.getBytes(StandardCharsets.UTF_8))
                .array();
        byte[] sig = crypto.signEd25519(identity.getPrivate(), message);
        byte[] identityPub = crypto.encodeEd25519Pub(identity.getPublic());

        // Pre-derive session key on the device side too (so we can seal blobs locally).
        byte[] shared = crypto.x25519Agree(kex.getPrivate(), crypto.decodeX25519Pub(serverKexPub));
        sessionKey = crypto.deriveSessionKey(shared, kexPubRaw, serverKexPub);

        return new PairingRequest(label,
                SyncCrypto.b64(identityPub),
                kexPubB64,
                pairingNonceB64,
                SyncCrypto.b64(sig));
    }

    public void rememberPairingResponse(String routingId, String deviceId) {
        this.routingId = routingId;
        this.deviceId = deviceId;
    }

    public SyncEnvelope sealEnvelope(SyncPayload payload) throws Exception {
        byte[] plaintext = mapper.writeValueAsBytes(payload);
        byte[] nonce = crypto.randomNonce();
        String nonceB64 = SyncCrypto.b64(nonce);
        SyncEnvelope tmp = new SyncEnvelope(SyncEnvelope.CURRENT_VERSION,
                routingId, deviceId, nonceB64, "", Instant.now());
        byte[] aad = (tmp.getVersion() + "|" + tmp.getRoutingId() + "|"
                + tmp.getSenderDeviceId() + "|" + tmp.getNonceB64())
                .getBytes(StandardCharsets.UTF_8);
        byte[] ct = crypto.seal(sessionKey, nonce, aad, plaintext);
        return new SyncEnvelope(SyncEnvelope.CURRENT_VERSION,
                routingId, deviceId, nonceB64, SyncCrypto.b64(ct), Instant.now());
    }

    public SyncCrypto crypto() { return crypto; }
    public SessionKeys sessionKey() { return sessionKey; }
    public String routingId() { return routingId; }
    public String deviceId() { return deviceId; }
}
