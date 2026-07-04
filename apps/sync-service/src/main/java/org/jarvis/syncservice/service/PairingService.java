package org.jarvis.syncservice.service;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.common.eventbus.AuditPublisher;
import org.jarvis.events.AuditEventType;
import org.jarvis.sync.PairingRequest;
import org.jarvis.sync.PairingResponse;
import org.jarvis.sync.crypto.SessionKeys;
import org.jarvis.sync.crypto.SyncCrypto;
import org.jarvis.syncservice.domain.PairedDevice;
import org.jarvis.syncservice.repository.PairingStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 12 — orchestrates the two-step pairing handshake.
 *
 * <ol>
 *   <li>{@link #initPairing()} — server generates a fresh X25519 kex
 *       keypair and a 32-byte pairing nonce. The pubkey + nonce go to
 *       the device; the privkey is parked in {@link PairingNonceStore}
 *       until the device returns.</li>
 *   <li>{@link #completePairing(PairingRequest)} — server verifies the
 *       device's Ed25519 signature over {@code nonce||deviceKexPub},
 *       computes the X25519 shared secret with the parked privkey,
 *       derives the ChaCha20-Poly1305 session key, persists the
 *       pairing, audit-emits {@code SYNC_DEVICE_PAIRED}, and returns
 *       the device its assigned alias and routingId.</li>
 * </ol>
 */
@Slf4j
@Service
public class PairingService {

    private final SyncCrypto crypto;
    private final PairingNonceStore nonceStore;
    private final PairingStore pairingStore;
    private final ObjectProvider<AuditPublisher> auditProvider;
    private final SecureRandom random = new SecureRandom();

    public PairingService(SyncCrypto crypto,
                          PairingNonceStore nonceStore,
                          PairingStore pairingStore,
                          ObjectProvider<AuditPublisher> auditProvider) {
        this.crypto = crypto;
        this.nonceStore = nonceStore;
        this.pairingStore = pairingStore;
        this.auditProvider = auditProvider;
    }

    /** Public DTO for the {@code /pairing/init} response. */
    public record InitResponse(String pairingNonceB64, String serverKexPubB64) {}

    public InitResponse initPairing() {
        KeyPair serverKex = crypto.generateKexKeyPair();
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String nonceB64 = SyncCrypto.b64(nonce);
        nonceStore.put(nonceB64, serverKex);
        return new InitResponse(
                nonceB64,
                SyncCrypto.b64(crypto.encodeX25519Pub(serverKex.getPublic())));
    }

    public PairingResponse completePairing(PairingRequest req) {
        // Load the server's kex keypair for this nonce (single-use, TTL'd).
        KeyPair serverKex = nonceStore.consume(req.getPairingNonceB64())
                .orElseThrow(() -> rejected("unknown_or_expired_pairing_nonce", req));

        byte[] identityPub = SyncCrypto.unb64(req.getIdentityPubB64());
        byte[] deviceKexPub = SyncCrypto.unb64(req.getKexPubB64());
        byte[] sig = SyncCrypto.unb64(req.getIdentitySigB64());

        byte[] message = ByteBuffer.allocate(req.getPairingNonceB64().length() + req.getKexPubB64().length())
                .put(req.getPairingNonceB64().getBytes(StandardCharsets.UTF_8))
                .put(req.getKexPubB64().getBytes(StandardCharsets.UTF_8))
                .array();
        PublicKey identity = crypto.decodeEd25519Pub(identityPub);
        if (!crypto.verifyEd25519(identity, message, sig)) {
            throw rejected("identity_signature_invalid", req);
        }

        PublicKey deviceKexPubKey = crypto.decodeX25519Pub(deviceKexPub);
        byte[] shared = crypto.x25519Agree(serverKex.getPrivate(), deviceKexPubKey);
        byte[] serverKexPub = crypto.encodeX25519Pub(serverKex.getPublic());
        SessionKeys sessionKey = crypto.deriveSessionKey(shared, deviceKexPub, serverKexPub);

        String deviceId = crypto.deviceAlias(identityPub);
        String routingId = "rt-" + UUID.randomUUID();

        PairedDevice device = new PairedDevice(deviceId, req.getDeviceLabel(),
                identityPub, deviceKexPub, serverKexPub, sessionKey,
                routingId, Instant.now());
        pairingStore.save(device);

        AuditPublisher audit = auditProvider.getIfAvailable();
        if (audit != null) {
            audit.audit(AuditEventType.SYNC_DEVICE_PAIRED, null, null, deviceId, null,
                    Map.of("deviceLabel", device.deviceLabel(),
                           "routingId", routingId,
                           "pairedAt", device.pairedAt().toString()));
        }
        log.info("paired device {} ({}) routingId={}", deviceId, device.deviceLabel(), routingId);

        return new PairingResponse(SyncCrypto.b64(serverKexPub), routingId, deviceId, device.pairedAt());
    }

    private PairingRejectedException rejected(String reason, PairingRequest req) {
        AuditPublisher audit = auditProvider.getIfAvailable();
        if (audit != null) {
            audit.audit(AuditEventType.SYNC_DEVICE_PAIRING_REJECTED, null, null,
                    req.getIdentityPubB64(), null,
                    Map.of("reason", reason, "deviceLabel", req.getDeviceLabel()));
        }
        return new PairingRejectedException(reason);
    }

    public static final class PairingRejectedException extends RuntimeException {
        public PairingRejectedException(String reason) { super(reason); }
    }
}
